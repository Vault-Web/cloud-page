package cloudpage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloudpage.dto.CreatedSecureSend;
import cloudpage.exceptions.InvalidSecureSendPasswordException;
import cloudpage.exceptions.SecureSendUnavailableException;
import cloudpage.model.SecureSend;
import cloudpage.model.User;
import cloudpage.repository.SecureSendRepository;
import cloudpage.repository.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class SecureSendServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");

  @Mock private SecureSendRepository secureSendRepository;
  @Mock private UserRepository userRepository;

  @TempDir Path tempDir;

  private SecureSendService service;
  private User owner;

  @BeforeEach
  void setUp() {
    service =
        new SecureSendService(
            secureSendRepository,
            userRepository,
            new FolderService(),
            new FileService(),
            new BCryptPasswordEncoder(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    owner = new User();
    owner.setId("owner-1");
    owner.setRootFolderPath(tempDir.toString());
    lenient()
        .when(secureSendRepository.save(any(SecureSend.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void createStoresOnlyHashedTokenAndPassword() throws Exception {
    Files.writeString(tempDir.resolve("report.pdf"), "report");

    CreatedSecureSend created =
        service.create(owner, "report.pdf", NOW.plusSeconds(3600), "secret");

    SecureSend send = created.secureSend();
    assertEquals("owner-1", send.getOwnerId());
    assertEquals("report.pdf", send.getRelativeFilePath());
    assertEquals("report.pdf", send.getDisplayName());
    assertNotEquals(created.token(), send.getTokenHash());
    assertEquals(64, send.getTokenHash().length());
    assertNotEquals("secret", send.getPasswordHash());
    assertTrue(new BCryptPasswordEncoder().matches("secret", send.getPasswordHash()));
  }

  @Test
  void resolveReturnsOnlyPinnedTargetFile() throws Exception {
    Path target = Files.writeString(tempDir.resolve("target.txt"), "allowed");
    Files.writeString(tempDir.resolve("other.txt"), "private");
    CreatedSecureSend created = service.create(owner, "target.txt", NOW.plusSeconds(3600), null);
    when(secureSendRepository.findByTokenHash(created.secureSend().getTokenHash()))
        .thenReturn(Optional.of(created.secureSend()));
    when(userRepository.findById("owner-1")).thenReturn(Optional.of(owner));

    var resolved = service.resolve(created.token(), null);

    assertEquals(target.toRealPath(), resolved.getPath());
    assertTrue(resolved.getFileResource().getResource().exists());
  }

  @Test
  void passwordProtectedLinkRejectsMissingAndWrongPasswords() throws Exception {
    Files.writeString(tempDir.resolve("secret.txt"), "data");
    CreatedSecureSend created =
        service.create(owner, "secret.txt", NOW.plusSeconds(3600), "correct");
    when(secureSendRepository.findByTokenHash(created.secureSend().getTokenHash()))
        .thenReturn(Optional.of(created.secureSend()));

    assertThrows(
        InvalidSecureSendPasswordException.class, () -> service.resolve(created.token(), null));
    assertThrows(
        InvalidSecureSendPasswordException.class, () -> service.resolve(created.token(), "wrong"));
  }

  @Test
  void expiredAndRevokedLinksAreUnavailable() throws Exception {
    Files.writeString(tempDir.resolve("doc.txt"), "data");
    CreatedSecureSend expired = service.create(owner, "doc.txt", NOW.plusSeconds(1), null);
    expired.secureSend().setExpiresAt(NOW);
    when(secureSendRepository.findByTokenHash(expired.secureSend().getTokenHash()))
        .thenReturn(Optional.of(expired.secureSend()));
    assertThrows(
        SecureSendUnavailableException.class, () -> service.resolve(expired.token(), null));

    CreatedSecureSend revoked = service.create(owner, "doc.txt", NOW.plusSeconds(60), null);
    revoked.secureSend().setRevokedAt(NOW);
    when(secureSendRepository.findByTokenHash(revoked.secureSend().getTokenHash()))
        .thenReturn(Optional.of(revoked.secureSend()));
    assertThrows(
        SecureSendUnavailableException.class, () -> service.resolve(revoked.token(), null));
  }

  @Test
  void revokeIsScopedToOwnerAndTakesEffectImmediately() {
    SecureSend send = new SecureSend();
    send.setId("send-1");
    when(secureSendRepository.findByIdAndOwnerId("send-1", "owner-1"))
        .thenReturn(Optional.of(send));

    service.revoke("owner-1", "send-1");

    assertEquals(NOW, send.getRevokedAt());
    verify(secureSendRepository).save(send);
    assertFalse(service.list("different-owner").stream().findAny().isPresent());
  }

  @Test
  void creationRejectsPathsOutsideOwnerRoot() throws Exception {
    Path outside = Files.createTempFile("outside-secure-send", ".txt");
    try {
      assertThrows(
          RuntimeException.class,
          () -> service.create(owner, outside.toString(), NOW.plusSeconds(60), null));
    } finally {
      Files.deleteIfExists(outside);
    }
  }
}
