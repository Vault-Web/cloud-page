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
import cloudpage.model.SharedResourceType;
import cloudpage.model.User;
import cloudpage.repository.SecureSendRepository;
import cloudpage.repository.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

    var resolved = service.resolve(created.token(), null, "");

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
        InvalidSecureSendPasswordException.class, () -> service.resolve(created.token(), null, ""));
    assertThrows(
        InvalidSecureSendPasswordException.class,
        () -> service.resolve(created.token(), "wrong", ""));
  }

  @Test
  void expiredAndRevokedLinksAreUnavailable() throws Exception {
    Files.writeString(tempDir.resolve("doc.txt"), "data");
    CreatedSecureSend expired = service.create(owner, "doc.txt", NOW.plusSeconds(1), null);
    expired.secureSend().setExpiresAt(NOW);
    when(secureSendRepository.findByTokenHash(expired.secureSend().getTokenHash()))
        .thenReturn(Optional.of(expired.secureSend()));
    assertThrows(
        SecureSendUnavailableException.class, () -> service.resolve(expired.token(), null, ""));

    CreatedSecureSend revoked = service.create(owner, "doc.txt", NOW.plusSeconds(60), null);
    revoked.secureSend().setRevokedAt(NOW);
    when(secureSendRepository.findByTokenHash(revoked.secureSend().getTokenHash()))
        .thenReturn(Optional.of(revoked.secureSend()));
    assertThrows(
        SecureSendUnavailableException.class, () -> service.resolve(revoked.token(), null, ""));
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
  void folderLinkListsItsContentsAndNavigatesIntoSubfolders() throws Exception {
    Path project = Files.createDirectory(tempDir.resolve("project"));
    Files.writeString(project.resolve("readme.md"), "readme");
    Path images = Files.createDirectory(project.resolve("images"));
    Files.writeString(images.resolve("photo.jpg"), "jpeg-bytes");
    Files.writeString(tempDir.resolve("private.txt"), "private");
    CreatedSecureSend created = service.create(owner, "project", NOW.plusSeconds(3600), null);
    when(secureSendRepository.findByTokenHash(created.secureSend().getTokenHash()))
        .thenReturn(Optional.of(created.secureSend()));
    when(userRepository.findById("owner-1")).thenReturn(Optional.of(owner));

    assertEquals(SharedResourceType.FOLDER, created.secureSend().getResourceType());
    assertEquals(
        SharedResourceType.FOLDER,
        service.describe(created.token()).resourceType(),
        "the landing page needs to know it is browsing a folder");

    var top = service.listFolder(created.token(), null, "");
    assertEquals(List.of("images", "readme.md"), top.stream().map(item -> item.getName()).toList());
    assertTrue(top.get(0).isDirectory());
    assertEquals("images", top.get(0).getPath());

    var nested = service.listFolder(created.token(), null, "images");
    assertEquals(1, nested.size());
    // Paths stay relative to the share so the recipient can pass them straight back.
    assertEquals("images/photo.jpg", nested.get(0).getPath());
    assertEquals(
        images.resolve("photo.jpg").toRealPath(),
        service.resolve(created.token(), null, "images/photo.jpg").getPath());
  }

  @Test
  void folderLinkConfinesEveryPathToTheSharedFolder() throws Exception {
    Path project = Files.createDirectory(tempDir.resolve("project"));
    Files.writeString(project.resolve("inside.txt"), "inside");
    Files.writeString(tempDir.resolve("private.txt"), "private");
    CreatedSecureSend created = service.create(owner, "project", NOW.plusSeconds(3600), null);
    when(secureSendRepository.findByTokenHash(created.secureSend().getTokenHash()))
        .thenReturn(Optional.of(created.secureSend()));
    when(userRepository.findById("owner-1")).thenReturn(Optional.of(owner));

    for (String escaping :
        List.of("../private.txt", "../", "/etc/passwd", "images/../../private.txt")) {
      assertThrows(
          SecureSendUnavailableException.class,
          () -> service.resolve(created.token(), null, escaping),
          escaping + " must not resolve through a folder link");
    }
    // The archive endpoint is confined the same way.
    assertThrows(
        SecureSendUnavailableException.class,
        () -> service.resolveFolderArchive(created.token(), null, ".."));
    assertEquals(
        project.toRealPath(), service.resolveFolderArchive(created.token(), null, "").folder());
  }

  @Test
  void folderLinkStillHonoursThePasswordOnEveryOperation() throws Exception {
    Path project = Files.createDirectory(tempDir.resolve("project"));
    Files.writeString(project.resolve("inside.txt"), "inside");
    CreatedSecureSend created = service.create(owner, "project", NOW.plusSeconds(3600), "correct");
    when(secureSendRepository.findByTokenHash(created.secureSend().getTokenHash()))
        .thenReturn(Optional.of(created.secureSend()));

    assertThrows(
        InvalidSecureSendPasswordException.class,
        () -> service.listFolder(created.token(), null, ""));
    assertThrows(
        InvalidSecureSendPasswordException.class,
        () -> service.resolve(created.token(), "wrong", "inside.txt"));
    assertThrows(
        InvalidSecureSendPasswordException.class,
        () -> service.resolveFolderArchive(created.token(), "wrong", ""));

    // Describing the link needs no password; it is what the landing page shows first.
    when(userRepository.findById("owner-1")).thenReturn(Optional.of(owner));
    assertTrue(service.describe(created.token()).passwordProtected());
  }

  @Test
  void fileLinkAcceptsNoChildPathAndTheTrashCannotBeShared() throws Exception {
    Files.writeString(tempDir.resolve("report.pdf"), "report");
    Path trash = Files.createDirectory(tempDir.resolve(TrashService.TRASH_DIR));
    Files.writeString(trash.resolve("deleted.txt"), "deleted");
    CreatedSecureSend created = service.create(owner, "report.pdf", NOW.plusSeconds(3600), null);
    when(secureSendRepository.findByTokenHash(created.secureSend().getTokenHash()))
        .thenReturn(Optional.of(created.secureSend()));
    when(userRepository.findById("owner-1")).thenReturn(Optional.of(owner));

    assertEquals(SharedResourceType.FILE, created.secureSend().getResourceType());
    assertThrows(
        SecureSendUnavailableException.class,
        () -> service.resolve(created.token(), null, "anything.txt"));
    assertThrows(
        SecureSendUnavailableException.class, () -> service.listFolder(created.token(), null, ""));
    assertThrows(
        RuntimeException.class,
        () -> service.create(owner, TrashService.TRASH_DIR, NOW.plusSeconds(60), null));
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
