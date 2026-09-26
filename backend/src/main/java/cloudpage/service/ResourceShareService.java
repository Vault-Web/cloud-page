package cloudpage.service;

import cloudpage.dto.FolderContentItemDto;
import cloudpage.dto.ResourceShareDto;
import cloudpage.dto.SharedFileResource;
import cloudpage.dto.SharedFolderResource;
import cloudpage.exceptions.InvalidPathException;
import cloudpage.exceptions.ResourceConflictException;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
  private final TrashService trashService;
  private final Clock clock;

  @Autowired
  public ResourceShareService(
      ResourceShareRepository shareRepository,
      UserRepository userRepository,
      FolderService folderService,
      FileService fileService,
      TrashService trashService) {
    this(
        shareRepository,
        userRepository,
        folderService,
        fileService,
        trashService,
        Clock.systemUTC());
  }

  ResourceShareService(
      ResourceShareRepository shareRepository,
      UserRepository userRepository,
      FolderService folderService,
      FileService fileService,
      TrashService trashService,
      Clock clock) {
    this.shareRepository = shareRepository;
    this.userRepository = userRepository;
    this.folderService = folderService;
    this.fileService = fileService;
    this.trashService = trashService;
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
    List<ResourceShare> shares =
        shareRepository.findByRecipientIdAndRevokedAtIsNullOrderByCreatedAtDesc(recipient.getId());
    // Look every owner up once. Both the existence check and the DTO need the
    // owner, and this listing runs on every visit to "Shared with me", so a
    // lookup per share would mean two queries per row.
    Map<String, User> owners =
        shares.stream()
            .map(ResourceShare::getOwnerId)
            .distinct()
            .map(userRepository::findById)
            .flatMap(Optional::stream)
            .collect(Collectors.toMap(User::getId, Function.identity()));
    return shares.stream()
        // Hide shares whose underlying file/folder the owner has since deleted or
        // moved, so the recipient's list stays in sync with reality.
        .filter(share -> targetStillExists(share, owners.get(share.getOwnerId())))
        .map(share -> toDto(share, owners.get(share.getOwnerId()), recipient))
        .toList();
  }

  /** A share whose owner is gone counts as missing, like one whose target was deleted. */
  private boolean targetStillExists(ResourceShare share, User owner) {
    if (owner == null) {
      return false;
    }
    try {
      Path ownerRoot = Paths.get(owner.getRootFolderPath()).toRealPath().normalize();
      Path target = ownerRoot.resolve(share.getRelativePath()).normalize();
      return target.startsWith(ownerRoot) && Files.exists(target);
    } catch (RuntimeException | IOException exception) {
      return false;
    }
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

  /**
   * Lets a recipient drop a share that was given to them. Only removes their own access; the
   * owner's data is untouched. Distinct from {@link #revoke}, which is the owner withdrawing
   * access.
   */
  public void leave(String recipientId, String shareId) {
    ResourceShare share =
        shareRepository
            .findByIdAndRecipientIdAndRevokedAtIsNull(shareId, recipientId)
            .orElseThrow(() -> new ResourceNotFoundException("Share", "id", shareId));
    share.setRevokedAt(clock.instant());
    shareRepository.save(share);
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
    editFile(shareId, recipient, childPath, file, null);
  }

  public void editFile(
      String shareId, User recipient, String childPath, MultipartFile file, String expectedETag)
      throws IOException {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("Replacement file must not be empty");
    }

    ResolvedShare resolved = resolve(shareId, recipient, childPath, SharePermission.EDIT);

    if (!Files.isRegularFile(resolved.target())) {
      throw new ResourceNotFoundException("Shared file", "path", childPath);
    }
    if (expectedETag != null) {
      String currentETag = fileService.loadAsResource(resolved.target()).getETag();

      if (!expectedETag.equals(currentETag)) {
        throw new ResourceConflictException("File has changed since it was last read");
      }
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

  /**
   * Adds a new file to a folder share. Requires EDIT. The file lands in the owner's storage and
   * counts against the owner's quota, so collaborators cannot fill their own space with someone
   * else's shared folder.
   */
  public void uploadToShare(String shareId, User recipient, String folderPath, MultipartFile file)
      throws IOException {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("Uploaded file must not be empty");
    }
    ResolvedShare resolved = resolve(shareId, recipient, folderPath, SharePermission.EDIT);
    if (!Files.isDirectory(resolved.target())) {
      throw new ResourceNotFoundException("Shared folder", "path", folderPath);
    }
    String relativeFolder = resolved.ownerRoot().relativize(resolved.target()).toString();
    fileService.uploadFile(
        resolved.ownerRoot().toString(), relativeFolder, file, resolved.ownerQuotaMb());
  }

  /** Creates a subfolder inside a folder share. Requires EDIT. */
  public void createFolderInShare(String shareId, User recipient, String parentPath, String name)
      throws IOException {
    validateSimpleName(name);
    ResolvedShare resolved = resolve(shareId, recipient, parentPath, SharePermission.EDIT);
    if (!Files.isDirectory(resolved.target())) {
      throw new ResourceNotFoundException("Shared folder", "path", parentPath);
    }
    String relativeParent = resolved.ownerRoot().relativize(resolved.target()).toString();
    folderService.createFolder(resolved.ownerRoot().toString(), relativeParent, name);
  }

  /**
   * Deletes a file or folder inside a share. Requires EDIT. The share root itself cannot be deleted
   * this way — that is the owner's resource, and the recipient's link to it is removed by revoking
   * the share, not by erasing the data.
   */
  /**
   * Deletes a file or folder inside a share. Requires EDIT.
   *
   * <p>Files are moved into the <b>owner's</b> trash, exactly as if the owner had deleted the file
   * themselves — a recipient with EDIT access must not be able to destroy the owner's data with no
   * recovery path. See the class-level note on folders below.
   *
   * <p><b>Folders are still hard-deleted here</b>, matching {@code FolderController}'s own delete
   * endpoint for the owner's own folders: this codebase has no folder-level trash mechanism at all
   * today ({@link TrashService#moveToTrash} only accepts a single regular file). Routing folder
   * deletes through trash is real follow-up work — it needs a trash design that can hold a whole
   * subtree, restore it, and account for its size against retention/quota — not a one-line change
   * here, so it is intentionally out of scope for this fix rather than silently expanding it.
   */
  public void deleteInShare(String shareId, User recipient, String childPath) throws IOException {
    ResolvedShare resolved = resolve(shareId, recipient, childPath, SharePermission.EDIT);
    requireInsideShare(resolved, "The shared resource itself cannot be deleted");
    String relative = resolved.ownerRoot().relativize(resolved.target()).toString();
    if (Files.isDirectory(resolved.target())) {
      folderService.deleteFolder(resolved.ownerRoot().toString(), relative);
    } else {
      trashService.moveToTrash(resolved.ownerRoot().toString(), resolved.ownerId(), relative);
    }
  }

  /** Renames a file or folder inside a share, keeping its parent. Requires EDIT. */
  public void renameInShare(String shareId, User recipient, String path, String newName)
      throws IOException {
    validateSimpleName(newName);
    ResolvedShare resolved = resolve(shareId, recipient, path, SharePermission.EDIT);
    requireInsideShare(resolved, "The shared resource itself cannot be renamed");
    Path parent = resolved.target().getParent();
    if (parent == null) {
      throw new ShareAccessDeniedException("The shared resource itself cannot be renamed");
    }
    String relSource = resolved.ownerRoot().relativize(resolved.target()).toString();
    String relTarget =
        resolved.ownerRoot().relativize(parent.resolve(newName).normalize()).toString();
    if (Files.isDirectory(resolved.target())) {
      folderService.renameOrMoveFolder(resolved.ownerRoot().toString(), relSource, relTarget);
    } else {
      fileService.renameOrMoveFile(resolved.ownerRoot().toString(), relSource, relTarget);
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
    return new ResolvedShare(
        ownerRoot, sharedRoot, targetReal, owner.getStorageQuotaMb(), owner.getId());
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

  /**
   * Guards the shared resource itself against a child operation. A child path such as {@code "."}
   * or {@code "a/.."} normalises back to the share root, which {@link #resolve} accepts because a
   * path starts with itself — so this has to compare the resolved paths rather than the incoming
   * string.
   */
  private void requireInsideShare(ResolvedShare resolved, String message) {
    if (resolved.target().equals(resolved.sharedRoot())) {
      throw new ShareAccessDeniedException(message);
    }
  }

  /**
   * Accepts a single name component. Names are joined onto a directory inside the share, while
   * {@link FolderService} and {@link FileService} confine paths only to the owner's root — a name
   * carrying separators would therefore land outside the share but still inside the owner's
   * storage.
   */
  private void validateSimpleName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Name must not be empty");
    }
    Path candidate;
    try {
      candidate = Path.of(name);
    } catch (java.nio.file.InvalidPathException exception) {
      throw new InvalidPathException("Invalid name: " + name);
    }
    if (candidate.isAbsolute()
        || candidate.getNameCount() != 1
        || name.contains("/")
        || name.contains("\\")
        || ".".equals(name)
        || "..".equals(name)) {
      throw new IllegalArgumentException("Invalid name: " + name);
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
        share.getRelativePath(),
        share.getResourceType(),
        Set.copyOf(share.getPermissions()),
        share.getCreatedAt(),
        share.getRevokedAt() != null);
  }

  private record ResolvedShare(
      Path ownerRoot, Path sharedRoot, Path target, Long ownerQuotaMb, String ownerId) {}
}
