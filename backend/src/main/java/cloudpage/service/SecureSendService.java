package cloudpage.service;

import cloudpage.dto.CreatedSecureSend;
import cloudpage.dto.FolderContentItemDto;
import cloudpage.dto.PublicSecureSendDto;
import cloudpage.dto.SecureSendDto;
import cloudpage.dto.SecureSendResource;
import cloudpage.dto.SharedFolderResource;
import cloudpage.exceptions.InvalidSecureSendPasswordException;
import cloudpage.exceptions.ResourceNotFoundException;
import cloudpage.exceptions.SecureSendUnavailableException;
import cloudpage.model.SecureSend;
import cloudpage.model.SharedResourceType;
import cloudpage.model.User;
import cloudpage.repository.SecureSendRepository;
import cloudpage.repository.UserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Creates, lists, revokes, and resolves expiring external links to files and folders. */
@Service
public class SecureSendService {

  private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
  private static final int TOKEN_BYTES = 32;

  private final SecureSendRepository secureSendRepository;
  private final UserRepository userRepository;
  private final FolderService folderService;
  private final FileService fileService;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;

  @Value("${cloudpage.secure-send.max-expiry:30d}")
  private Duration maxExpiry;

  @Value("${cloudpage.secure-send.retention:30d}")
  private Duration retention;

  @Autowired
  public SecureSendService(
      SecureSendRepository secureSendRepository,
      UserRepository userRepository,
      FolderService folderService,
      FileService fileService,
      PasswordEncoder passwordEncoder) {
    this(
        secureSendRepository,
        userRepository,
        folderService,
        fileService,
        passwordEncoder,
        Clock.systemUTC());
  }

  SecureSendService(
      SecureSendRepository secureSendRepository,
      UserRepository userRepository,
      FolderService folderService,
      FileService fileService,
      PasswordEncoder passwordEncoder,
      Clock clock) {
    this.secureSendRepository = secureSendRepository;
    this.userRepository = userRepository;
    this.folderService = folderService;
    this.fileService = fileService;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
    this.maxExpiry = Duration.ofDays(30);
    this.retention = Duration.ofDays(30);
  }

  /**
   * Creates a link to a file or a folder. A folder link grants the recipient the whole subtree
   * below it — browsing, single downloads and a ZIP of the lot — so the path is pinned to its
   * canonical location here and every later request is confined to it.
   */
  public CreatedSecureSend create(
      User owner, String relativeFilePath, Instant expiresAt, String password) throws IOException {
    Instant now = clock.instant();
    if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(maxExpiry))) {
      throw new IllegalArgumentException(
          "Expiry must be in the future and no more than " + maxExpiry.toDays() + " days away");
    }

    Path requested = Paths.get(owner.getRootFolderPath(), relativeFilePath).normalize();
    folderService.validatePath(owner.getRootFolderPath(), requested);
    rejectTrashPath(parseChildPath(relativeFilePath));
    if (!Files.isReadable(requested)
        || !(Files.isRegularFile(requested) || Files.isDirectory(requested))) {
      throw new ResourceNotFoundException("Resource", "Path", relativeFilePath);
    }

    // Resolve and store the canonical target so the original symlink cannot later redirect the
    // link, while retaining a portable path relative to the owner's root.
    Path rootReal = Paths.get(owner.getRootFolderPath()).toRealPath().normalize();
    Path targetReal = requested.toRealPath().normalize();
    if (!targetReal.startsWith(rootReal)) {
      throw new ResourceNotFoundException("Resource", "Path", relativeFilePath);
    }
    if (targetReal.equals(rootReal)) {
      throw new IllegalArgumentException("The storage root itself cannot be shared as a link");
    }

    String token = generateToken();
    SecureSend send = new SecureSend();
    send.setId(UUID.randomUUID().toString());
    send.setOwnerId(owner.getId());
    send.setTokenHash(hashToken(token));
    send.setRelativeFilePath(rootReal.relativize(targetReal).toString());
    send.setDisplayName(targetReal.getFileName().toString());
    send.setResourceType(
        Files.isDirectory(targetReal) ? SharedResourceType.FOLDER : SharedResourceType.FILE);
    send.setCreatedAt(now);
    send.setExpiresAt(expiresAt);
    if (StringUtils.hasText(password)) {
      send.setPasswordHash(passwordEncoder.encode(password));
    }

    return new CreatedSecureSend(secureSendRepository.save(send), token);
  }

  public List<SecureSendDto> list(String ownerId) {
    return secureSendRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
        .map(send -> toDto(send, null))
        .toList();
  }

  public void revoke(String ownerId, String id) {
    SecureSend send =
        secureSendRepository
            .findByIdAndOwnerId(id, ownerId)
            .orElseThrow(() -> new ResourceNotFoundException("SecureSend", "id", id));
    if (send.getRevokedAt() == null) {
      send.setRevokedAt(clock.instant());
      secureSendRepository.save(send);
    }
  }

  /**
   * Describes a link without checking the password, so the public landing page can name the shared
   * resource and state that a password is needed. Expired, revoked and unknown tokens are
   * indistinguishable from the outside.
   */
  public PublicSecureSendDto describe(String token) {
    SecureSend send = requireActive(token);
    ResolvedSend resolved = resolveWithin(send, "");
    boolean directory = Files.isDirectory(resolved.target());
    // A folder is described without a size: totalling it means walking the whole
    // subtree, and this page is reachable by anyone holding the link, before any
    // password has been entered.
    long size = 0L;
    if (!directory) {
      try {
        size = Files.size(resolved.target());
      } catch (IOException exception) {
        throw new SecureSendUnavailableException();
      }
    }
    return new PublicSecureSendDto(
        send.getDisplayName(),
        size,
        send.getExpiresAt(),
        send.getPasswordHash() != null,
        directory ? SharedResourceType.FOLDER : SharedResourceType.FILE);
  }

  /** Resolves the shared file itself, or one file inside a shared folder. */
  public SecureSendResource resolve(String token, String password, String childPath) {
    ResolvedSend resolved = resolveWithin(requireAccess(token, password), childPath);
    if (!Files.isRegularFile(resolved.target())) {
      throw new SecureSendUnavailableException();
    }
    try {
      return new SecureSendResource(
          resolved.target(), fileService.loadAsResource(resolved.target()));
    } catch (IOException | RuntimeException exception) {
      throw new SecureSendUnavailableException();
    }
  }

  /**
   * Lists one level of a shared folder so the recipient can navigate it. Symlinks and the trash are
   * left out, and every entry is checked to still sit inside the share before it is reported.
   */
  public List<FolderContentItemDto> listFolder(String token, String password, String childPath)
      throws IOException {
    ResolvedSend resolved = resolveWithin(requireAccess(token, password), childPath);
    if (!Files.isDirectory(resolved.target())) {
      throw new SecureSendUnavailableException();
    }
    try (var children = Files.list(resolved.target())) {
      return children
          .filter(path -> !Files.isSymbolicLink(path))
          .filter(path -> !TrashService.TRASH_DIR.equals(path.getFileName().toString()))
          .map(path -> toContentItem(resolved, path, childPath))
          .sorted(
              Comparator.comparing(FolderContentItemDto::getName, String.CASE_INSENSITIVE_ORDER))
          .toList();
    }
  }

  private FolderContentItemDto toContentItem(ResolvedSend resolved, Path path, String childPath) {
    try {
      Path real = path.toRealPath().normalize();
      if (!real.startsWith(resolved.target()) || !real.startsWith(resolved.sharedRoot())) {
        throw new SecureSendUnavailableException();
      }
      BasicFileAttributes attributes =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      boolean directory = attributes.isDirectory();
      String relative = resolved.target().relativize(real).toString().replace('\\', '/');
      String prefix = childPath == null || childPath.isBlank() ? "" : childPath + "/";
      return new FolderContentItemDto(
          path.getFileName().toString(),
          prefix + relative,
          directory,
          directory ? 0L : attributes.size(),
          directory ? null : Files.probeContentType(path),
          attributes.lastModifiedTime().toMillis());
    } catch (IOException exception) {
      throw new SecureSendUnavailableException();
    }
  }

  /** The folder to stream as a ZIP: the whole share, or one folder inside it. */
  public SharedFolderResource resolveFolderArchive(
      String token, String password, String childPath) {
    ResolvedSend resolved = resolveWithin(requireAccess(token, password), childPath);
    if (!Files.isDirectory(resolved.target())) {
      throw new SecureSendUnavailableException();
    }
    return new SharedFolderResource(resolved.ownerRoot(), resolved.target());
  }

  private SecureSend requireActive(String token) {
    SecureSend send =
        secureSendRepository
            .findByTokenHash(hashToken(token))
            .orElseThrow(SecureSendUnavailableException::new);
    if (send.getRevokedAt() != null || !send.getExpiresAt().isAfter(clock.instant())) {
      throw new SecureSendUnavailableException();
    }
    return send;
  }

  private SecureSend requireAccess(String token, String password) {
    SecureSend send = requireActive(token);
    if (send.getPasswordHash() != null
        && (!StringUtils.hasText(password)
            || !passwordEncoder.matches(password, send.getPasswordHash()))) {
      throw new InvalidSecureSendPasswordException();
    }
    return send;
  }

  /**
   * Resolves a path inside a link and confines it to the shared resource. A file link accepts no
   * child path at all; a folder link accepts one that stays below the shared folder, checked again
   * after symlinks have been resolved. Anything else is reported as an unavailable link, so a
   * probing recipient learns nothing about the owner's storage.
   */
  private ResolvedSend resolveWithin(SecureSend send, String childPath) {
    User owner =
        userRepository.findById(send.getOwnerId()).orElseThrow(SecureSendUnavailableException::new);
    try {
      Path rootReal = Paths.get(owner.getRootFolderPath()).toRealPath().normalize();
      Path requested = rootReal.resolve(send.getRelativeFilePath()).normalize();
      folderService.validatePath(rootReal.toString(), requested);
      Path sharedRoot = requested.toRealPath().normalize();
      if (!sharedRoot.startsWith(rootReal)) {
        throw new SecureSendUnavailableException();
      }

      Path child = parseChildPath(childPath);
      rejectTrashPath(child);
      if (!child.toString().isEmpty() && !Files.isDirectory(sharedRoot)) {
        throw new SecureSendUnavailableException();
      }
      Path target = sharedRoot.resolve(child).normalize();
      if (!target.startsWith(sharedRoot) || !Files.exists(target)) {
        throw new SecureSendUnavailableException();
      }
      Path targetReal = target.toRealPath().normalize();
      if (!targetReal.startsWith(sharedRoot) || !targetReal.startsWith(rootReal)) {
        throw new SecureSendUnavailableException();
      }
      return new ResolvedSend(rootReal, sharedRoot, targetReal);
    } catch (IOException | RuntimeException exception) {
      throw new SecureSendUnavailableException();
    }
  }

  private Path parseChildPath(String value) {
    try {
      Path path = value == null || value.isBlank() ? Path.of("") : Path.of(value);
      if (path.isAbsolute()) {
        throw new SecureSendUnavailableException();
      }
      return path;
    } catch (java.nio.file.InvalidPathException exception) {
      throw new SecureSendUnavailableException();
    }
  }

  private void rejectTrashPath(Path path) {
    for (Path part : path) {
      if (TrashService.TRASH_DIR.equals(part.toString())) {
        throw new SecureSendUnavailableException();
      }
    }
  }

  /** The owner's root, the pinned shared resource, and the requested path inside it. */
  private record ResolvedSend(Path ownerRoot, Path sharedRoot, Path target) {}

  public SecureSendDto toDto(SecureSend send, String url) {
    return new SecureSendDto(
        send.getId(),
        url,
        send.getDisplayName(),
        send.resourceTypeOrFile(),
        send.getCreatedAt(),
        send.getExpiresAt(),
        send.getPasswordHash() != null,
        send.getRevokedAt() != null);
  }

  @Scheduled(cron = "${cloudpage.secure-send.cleanup-cron:0 30 3 * * *}")
  public void deleteExpiredRecords() {
    secureSendRepository.deleteByExpiresAtBefore(clock.instant().minus(retention));
  }

  private String generateToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    TOKEN_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String hashToken(String token) {
    if (!StringUtils.hasText(token)) {
      throw new SecureSendUnavailableException();
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 algorithm not available", exception);
    }
  }
}
