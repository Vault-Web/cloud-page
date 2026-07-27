package cloudpage.service;

import cloudpage.dto.FolderContentItemDto;
import cloudpage.dto.ResourceShareDto;
import cloudpage.dto.SharedFileResource;
import cloudpage.dto.SharedFolderResource;
import cloudpage.exceptions.InvalidPathException;
import cloudpage.exceptions.ResourceNotFoundException;
import cloudpage.exceptions.ShareAccessDeniedException;
import cloudpage.model.ResourceShare;
import cloudpage.model.SharePermission;
import cloudpage.model.SharedResourceType;
import cloudpage.model.User;
import cloudpage.repository.ResourceShareRepository;
import cloudpage.repository.UserRepository;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Creates and enforces authenticated, recipient-specific file and folder grants. */
@Service
public class ResourceShareService {

  private final ResourceShareRepository shareRepository;
  private final UserRepository userRepository;
  private final FolderService folderService;
  private final FileService fileService;
  private final Clock clock;

  @Autowired
  public ResourceShareService(
      ResourceShareRepository shareRepository,
      UserRepository userRepository,
      FolderService folderService,
      FileService fileService) {
    this(shareRepository, userRepository, folderService, fileService, Clock.systemUTC());
  }

  ResourceShareService(
      ResourceShareRepository shareRepository,
      UserRepository userRepository,
      FolderService folderService,
      FileService fileService,
      Clock clock) {
    this.shareRepository = shareRepository;
    this.userRepository = userRepository;
    this.folderService = folderService;
    this.fileService = fileService;
    this.clock = clock;
  }

  public ResourceShareDto create(
      User owner, String path, String recipientUsername, Set<SharePermission> permissions)
      throws IOException {
    User recipient =
        userRepository
            .findByUsername(recipientUsername)
            .orElseThrow(
                () -> new ResourceNotFoundException("User", "Username", recipientUsername));
    if (owner.getId().equals(recipient.getId())) {
      throw new IllegalArgumentException("A resource cannot be shared with its owner");
    }
    if (permissions == null
        || permissions.isEmpty()
        || permissions.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("At least one valid permission is required");
    }

    Path rootReal = Paths.get(owner.getRootFolderPath()).toRealPath().normalize();
    Path requested = parseRelativePath(path, "resource path");
    rejectTrashPath(requested);
    Path target = rootReal.resolve(requested).normalize();
    folderService.validatePath(rootReal.toString(), target);
    if (!Files.exists(target)) {
      throw new ResourceNotFoundException("Resource", "Path", path);
    }
    Path targetReal = target.toRealPath().normalize();
    if (!targetReal.startsWith(rootReal)) {
      throw new InvalidPathException("Path traversal attempt detected: " + path);
    }

    SharedResourceType type;
    if (Files.isRegularFile(targetReal)) {
      type = SharedResourceType.FILE;
    } else if (Files.isDirectory(targetReal)) {
      type = SharedResourceType.FOLDER;
    } else {
      throw new ResourceNotFoundException("Resource", "Path", path);
    }

    ResourceShare share = new ResourceShare();
    share.setId(UUID.randomUUID().toString());
    share.setOwnerId(owner.getId());
    share.setRecipientId(recipient.getId());
    share.setRelativePath(rootReal.relativize(targetReal).toString());
    share.setDisplayName(targetReal.getFileName().toString());
    share.setResourceType(type);
    share.setPermissions(new HashSet<>(permissions));
    share.setCreatedAt(clock.instant());
    var existing =
        shareRepository.findByOwnerIdAndRecipientIdAndRelativePathAndRevokedAtIsNull(
            owner.getId(), recipient.getId(), share.getRelativePath());
    if (existing.isPresent()) {
      ResourceShare activeShare = existing.get();
      activeShare.setPermissions(new HashSet<>(permissions));
      return toDto(shareRepository.save(activeShare), owner, recipient);
    }
    return toDto(shareRepository.save(share), owner, recipient);
  }

  public List<ResourceShareDto> listOwned(User owner) {
    return shareRepository.findByOwnerIdOrderByCreatedAtDesc(owner.getId()).stream()
        .map(share -> toDto(share, owner, findUser(share.getRecipientId())))
        .toList();
  }

  public List<ResourceShareDto> listReceived(User recipient) {
    return shareRepository
        .findByRecipientIdAndRevokedAtIsNullOrderByCreatedAtDesc(recipient.getId())
        .stream()
        .map(share -> toDto(share, findUser(share.getOwnerId()), recipient))
        .toList();
  }

  public void revoke(String ownerId, String shareId) {
    ResourceShare share =
        shareRepository
            .findByIdAndOwnerId(shareId, ownerId)
            .orElseThrow(() -> new ResourceNotFoundException("Share", "id", shareId));
    if (share.getRevokedAt() == null) {
      share.setRevokedAt(clock.instant());
      shareRepository.save(share);
    }
  }

  public SharedFileResource resolveFile(
      String shareId, User recipient, String childPath, SharePermission permission)
      throws IOException {
    ResolvedShare resolved = resolve(shareId, recipient, childPath, permission);
    if (!Files.isRegularFile(resolved.target())) {
      throw new ResourceNotFoundException("Shared file", "path", childPath);
    }
    return new SharedFileResource(resolved.target(), fileService.loadAsResource(resolved.target()));
  }

  public SharedFolderResource resolveFolderDownload(
      String shareId, User recipient, String childPath) throws IOException {
    ResolvedShare resolved = resolve(shareId, recipient, childPath, SharePermission.DOWNLOAD);
    if (!Files.isDirectory(resolved.target())) {
      throw new ResourceNotFoundException("Shared folder", "path", childPath);
    }
    return new SharedFolderResource(resolved.ownerRoot(), resolved.target());
  }

  public void editFile(String shareId, User recipient, String childPath, MultipartFile file)
      throws IOException {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("Replacement file must not be empty");
    }
    ResolvedShare resolved = resolve(shareId, recipient, childPath, SharePermission.EDIT);
    if (!Files.isRegularFile(resolved.target())) {
      throw new ResourceNotFoundException("Shared file", "path", childPath);
    }
    long existingSize = Files.size(resolved.target());
    fileService.validateReplacementWithinQuota(
        resolved.ownerRoot().toString(), existingSize, file.getSize(), resolved.ownerQuotaMb());
    try (var input = file.getInputStream();
        FileChannel channel =
            FileChannel.open(
                resolved.target(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
      Path currentTarget = resolved.target().toRealPath(LinkOption.NOFOLLOW_LINKS).normalize();
      if (Files.isSymbolicLink(resolved.target())
          || !currentTarget.startsWith(resolved.sharedRoot())
          || !currentTarget.startsWith(resolved.ownerRoot())) {
        throw new InvalidPathException("Shared edit target is no longer inside its share");
      }
      channel.truncate(0);
      input.transferTo(Channels.newOutputStream(channel));
    }
  }

  public List<FolderContentItemDto> listFolder(String shareId, User recipient, String childPath)
      throws IOException {
    ResolvedShare resolved = resolve(shareId, recipient, childPath, SharePermission.VIEW);
    if (!Files.isDirectory(resolved.target())) {
      throw new ResourceNotFoundException("Shared folder", "path", childPath);
    }
    try (var children = Files.list(resolved.target())) {
      return children
          .filter(path -> !Files.isSymbolicLink(path))
          .filter(path -> !TrashService.TRASH_DIR.equals(path.getFileName().toString()))
          .map(
              path -> {
                try {
                  Path real = path.toRealPath().normalize();
                  if (!real.startsWith(resolved.target())
                      || !real.startsWith(resolved.ownerRoot())) {
                    throw new InvalidPathException("Shared folder contains an invalid path");
                  }
                  BasicFileAttributes attributes =
                      Files.readAttributes(
                          path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                  boolean directory = attributes.isDirectory();
                  String relative =
                      resolved.target().relativize(real).toString().replace('\\', '/');
                  String prefix = childPath == null || childPath.isBlank() ? "" : childPath + "/";
                  return new FolderContentItemDto(
                      path.getFileName().toString(),
                      prefix + relative,
                      directory,
                      directory ? 0L : attributes.size(),
                      directory ? null : Files.probeContentType(path),
                      attributes.lastModifiedTime().toMillis());
                } catch (IOException exception) {
                  throw new InvalidPathException("Unable to read shared folder entry");
                }
              })
          .sorted(
              java.util.Comparator.comparing(
                  FolderContentItemDto::getName, String.CASE_INSENSITIVE_ORDER))
          .toList();
    }
  }

  private ResolvedShare resolve(
      String shareId, User recipient, String childPath, SharePermission permission)
      throws IOException {
    ResourceShare share =
        shareRepository
            .findByIdAndRecipientIdAndRevokedAtIsNull(shareId, recipient.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Share", "id", shareId));
    if (!share.getPermissions().contains(permission)) {
      throw new ShareAccessDeniedException("The share does not grant " + permission + " access");
    }

    User owner = findUser(share.getOwnerId());
    Path ownerRoot = Paths.get(owner.getRootFolderPath()).toRealPath().normalize();
    Path sharedRoot = ownerRoot.resolve(share.getRelativePath()).normalize();
    if (!Files.exists(sharedRoot)) {
      throw new ResourceNotFoundException("Shared resource", "id", shareId);
    }
    sharedRoot = sharedRoot.toRealPath().normalize();
    if (!sharedRoot.startsWith(ownerRoot)) {
      throw new ResourceNotFoundException("Shared resource", "id", shareId);
    }

    Path child = parseRelativePath(childPath, "shared child path");
    rejectTrashPath(child);
    if (share.getResourceType() == SharedResourceType.FILE
        && childPath != null
        && !childPath.isBlank()) {
      throw new ResourceNotFoundException("Shared file", "path", childPath);
    }
    Path target = sharedRoot.resolve(child).normalize();
    if (!target.startsWith(sharedRoot) || !Files.exists(target)) {
      throw new ResourceNotFoundException("Shared resource", "path", childPath);
    }
    Path targetReal = target.toRealPath().normalize();
    if (!targetReal.startsWith(sharedRoot) || !targetReal.startsWith(ownerRoot)) {
      throw new ResourceNotFoundException("Shared resource", "path", childPath);
    }
    return new ResolvedShare(ownerRoot, sharedRoot, targetReal, owner.getStorageQuotaMb());
  }

  private Path parseRelativePath(String value, String label) {
    try {
      Path path = value == null || value.isBlank() ? Path.of("") : Path.of(value);
      if (path.isAbsolute()) {
        throw new InvalidPathException("Invalid " + label + ": " + value);
      }
      return path;
    } catch (java.nio.file.InvalidPathException exception) {
      throw new InvalidPathException("Invalid " + label + ": " + value);
    }
  }

  private void rejectTrashPath(Path path) {
    for (Path part : path) {
      if (TrashService.TRASH_DIR.equals(part.toString())) {
        throw new InvalidPathException("Trash resources cannot be shared or accessed");
      }
    }
  }

  private User findUser(String id) {
    return userRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
  }

  private ResourceShareDto toDto(ResourceShare share, User owner, User recipient) {
    return new ResourceShareDto(
        share.getId(),
        owner.getUsername(),
        recipient.getUsername(),
        share.getDisplayName(),
        share.getResourceType(),
        Set.copyOf(share.getPermissions()),
        share.getCreatedAt(),
        share.getRevokedAt() != null);
  }

  private record ResolvedShare(Path ownerRoot, Path sharedRoot, Path target, Long ownerQuotaMb) {}
}
