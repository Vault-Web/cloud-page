package cloudpage.service;

import cloudpage.dto.CreatedSecureSend;
import cloudpage.dto.SecureSendDto;
import cloudpage.dto.SecureSendResource;
import cloudpage.exceptions.InvalidSecureSendPasswordException;
import cloudpage.exceptions.ResourceNotFoundException;
import cloudpage.exceptions.SecureSendUnavailableException;
import cloudpage.model.SecureSend;
import cloudpage.model.User;
import cloudpage.repository.SecureSendRepository;
import cloudpage.repository.UserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Creates, lists, revokes, and resolves expiring external links to individual files. */
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

  public CreatedSecureSend create(
      User owner, String relativeFilePath, Instant expiresAt, String password) throws IOException {
    Instant now = clock.instant();
    if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(maxExpiry))) {
      throw new IllegalArgumentException(
          "Expiry must be in the future and no more than " + maxExpiry.toDays() + " days away");
    }

    Path requested = Paths.get(owner.getRootFolderPath(), relativeFilePath).normalize();
    folderService.validatePath(owner.getRootFolderPath(), requested);
    if (!Files.isRegularFile(requested) || !Files.isReadable(requested)) {
      throw new ResourceNotFoundException("File", "FilePath", relativeFilePath);
    }

    // Resolve and store the canonical target so the original symlink cannot later redirect the
    // link, while retaining a portable path relative to the owner's root.
    Path rootReal = Paths.get(owner.getRootFolderPath()).toRealPath().normalize();
    Path fileReal = requested.toRealPath().normalize();
    if (!fileReal.startsWith(rootReal)) {
      throw new ResourceNotFoundException("File", "FilePath", relativeFilePath);
    }

    String token = generateToken();
    SecureSend send = new SecureSend();
    send.setId(UUID.randomUUID().toString());
    send.setOwnerId(owner.getId());
    send.setTokenHash(hashToken(token));
    send.setRelativeFilePath(rootReal.relativize(fileReal).toString());
    send.setDisplayName(fileReal.getFileName().toString());
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

  public SecureSendResource resolve(String token, String password) {
    SecureSend send =
        secureSendRepository
            .findByTokenHash(hashToken(token))
            .orElseThrow(SecureSendUnavailableException::new);
    Instant now = clock.instant();
    if (send.getRevokedAt() != null || !send.getExpiresAt().isAfter(now)) {
      throw new SecureSendUnavailableException();
    }
    if (send.getPasswordHash() != null
        && (!StringUtils.hasText(password)
            || !passwordEncoder.matches(password, send.getPasswordHash()))) {
      throw new InvalidSecureSendPasswordException();
    }

    User owner =
        userRepository.findById(send.getOwnerId()).orElseThrow(SecureSendUnavailableException::new);
    try {
      Path rootReal = Paths.get(owner.getRootFolderPath()).toRealPath().normalize();
      Path requested = rootReal.resolve(send.getRelativeFilePath()).normalize();
      folderService.validatePath(rootReal.toString(), requested);
      Path fileReal = requested.toRealPath().normalize();
      if (!fileReal.startsWith(rootReal) || !Files.isRegularFile(fileReal)) {
        throw new SecureSendUnavailableException();
      }
      return new SecureSendResource(fileReal, fileService.loadAsResource(fileReal));
    } catch (IOException | RuntimeException exception) {
      throw new SecureSendUnavailableException();
    }
  }

  public SecureSendDto toDto(SecureSend send, String url) {
    return new SecureSendDto(
        send.getId(),
        url,
        send.getDisplayName(),
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
