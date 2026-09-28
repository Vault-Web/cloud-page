package cloudpage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloudpage.exceptions.InvalidPathException;
import cloudpage.exceptions.ResourceConflictException;
import cloudpage.exceptions.ResourceNotFoundException;
import cloudpage.exceptions.ShareAccessDeniedException;
import cloudpage.model.ResourceShare;
import cloudpage.model.SharePermission;
import cloudpage.model.SharedResourceType;
import cloudpage.model.User;
import cloudpage.repository.ResourceShareRepository;
import cloudpage.repository.TrashEntryRepository;
import cloudpage.repository.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class ResourceShareServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

  @Mock private ResourceShareRepository shareRepository;
  @Mock private UserRepository userRepository;
  @Mock private TrashEntryRepository trashEntryRepository;

  @TempDir Path ownerRoot;

  private ResourceShareService service;
  private User owner;
  private User recipient;

  @BeforeEach
  void setUp() {
    FolderService folderService = new FolderService();
    // A real TrashService (not a mock) so trashing a file has the same on-disk effect it would
    // have for a real user: the file actually moves into .trash. Only its own repository
    // dependency is mocked, since that would otherwise need a database.
    TrashService trashService =
        new TrashService(trashEntryRepository, userRepository, folderService, new FileService());
    service =
        new ResourceShareService(
            shareRepository,
            userRepository,
            folderService,
            new FileService(),
            trashService,
            Clock.fixed(NOW, ZoneOffset.UTC));
    owner = user("owner-1", "alice", ownerRoot);
    recipient = user("recipient-1", "bob", ownerRoot.resolve("unused"));
    lenient().when(userRepository.findByUsername("bob")).thenReturn(Optional.of(recipient));
    lenient().when(userRepository.findById("owner-1")).thenReturn(Optional.of(owner));
    lenient().when(userRepository.findById("recipient-1")).thenReturn(Optional.of(recipient));
    lenient()
        .when(shareRepository.save(any(ResourceShare.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void ownerCanShareOwnedFileWithRegisteredRecipient() throws Exception {
    Files.writeString(ownerRoot.resolve("report.pdf"), "report");

    var dto =
        service.create(
            owner, "report.pdf", "bob", Set.of(SharePermission.VIEW, SharePermission.DOWNLOAD));

    assertEquals("alice", dto.ownerUsername());
    assertEquals("bob", dto.recipientUsername());
    assertEquals(SharedResourceType.FILE, dto.resourceType());
    assertEquals(Set.of(SharePermission.VIEW, SharePermission.DOWNLOAD), dto.permissions());
    verify(shareRepository).save(any(ResourceShare.class));
  }

  @Test
  void duplicateActiveShareIsReusedAndItsPermissionsAreUpdated() throws Exception {
    Files.writeString(ownerRoot.resolve("report.pdf"), "report");
    ResourceShare existing = share("existing-share", "report.pdf", SharedResourceType.FILE);
    existing.setPermissions(Set.of(SharePermission.VIEW));
    when(shareRepository.findByOwnerIdAndRecipientIdAndRelativePathAndRevokedAtIsNull(
            "owner-1", "recipient-1", "report.pdf"))
        .thenReturn(Optional.of(existing));

    var dto = service.create(owner, "report.pdf", "bob", Set.of(SharePermission.DOWNLOAD));

    assertEquals("existing-share", dto.id());
    assertEquals(Set.of(SharePermission.DOWNLOAD), existing.getPermissions());
    verify(shareRepository, times(1)).save(existing);
  }

  @Test
  void ownerCannotSharePathOutsideTheirRoot() throws Exception {
    Path outside = Files.createTempFile("outside-share", ".txt");
    try {
      assertThrows(
          InvalidPathException.class,
          () -> service.create(owner, outside.toString(), "bob", Set.of(SharePermission.VIEW)));
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void onlyNamedRecipientCanResolveExplicitlySharedFile() throws Exception {
    Path target = Files.writeString(ownerRoot.resolve("shared.txt"), "allowed");
    Files.writeString(ownerRoot.resolve("private.txt"), "private");
    ResourceShare share = share("share-1", "shared.txt", SharedResourceType.FILE);
    when(shareRepository.findByIdAndRecipientIdAndRevokedAtIsNull("share-1", "recipient-1"))
        .thenReturn(Optional.of(share));

    var resolved = service.resolveFile("share-1", recipient, "", SharePermission.DOWNLOAD);

    assertEquals(target.toRealPath(), resolved.path());
    User stranger = user("stranger-1", "mallory", ownerRoot.resolve("unused-2"));
    assertThrows(
        ResourceNotFoundException.class,
        () -> service.resolveFile("share-1", stranger, "", SharePermission.DOWNLOAD));
  }

  @Test
  void permissionsAreEnforcedForEveryOperation() throws Exception {
    Files.writeString(ownerRoot.resolve("preview.txt"), "preview");
    ResourceShare share = share("share-1", "preview.txt", SharedResourceType.FILE);
    share.setPermissions(Set.of(SharePermission.VIEW));
    when(shareRepository.findByIdAndRecipientIdAndRevokedAtIsNull("share-1", "recipient-1"))
        .thenReturn(Optional.of(share));

    assertTrue(
        service
            .resolveFile("share-1", recipient, "", SharePermission.VIEW)
            .fileResource()
            .getResource()
            .exists());
    assertThrows(
        ShareAccessDeniedException.class,
        () -> service.resolveFile("share-1", recipient, "", SharePermission.DOWNLOAD));
    assertThrows(
        ShareAccessDeniedException.class,
        () ->
            service.editFile(
                "share-1", recipient, "", new MockMultipartFile("file", "updated".getBytes())));
  }

  @Test
  void editPermissionCanReplaceOnlyAnExistingFileInsideSharedBoundary() throws Exception {
    Path project = Files.createDirectory(ownerRoot.resolve("project"));
    Path target = Files.writeString(project.resolve("notes.txt"), "before");
    Files.writeString(ownerRoot.resolve("private.txt"), "private");
    ResourceShare share = share("share-1", "project", SharedResourceType.FOLDER);
    share.setPermissions(Set.of(SharePermission.EDIT));
    when(shareRepository.findByIdAndRecipientIdAndRevokedAtIsNull("share-1", "recipient-1"))
        .thenReturn(Optional.of(share));

    service.editFile(
        "share-1",
        recipient,
        "notes.txt",
        new MockMultipartFile("file", "notes.txt", "text/plain", "after".getBytes()));

    assertEquals("after", Files.readString(target));
    assertThrows(
        ResourceNotFoundException.class,
        () ->
            service.editFile(
                "share-1",
                recipient,
                "../private.txt",
                new MockMultipartFile("file", "blocked".getBytes())));
    assertEquals("private", Files.readString(ownerRoot.resolve("private.txt")));
  }

  @Test
  void editRejectsStaleETag() throws Exception {
    Path target = Files.writeString(ownerRoot.resolve("notes.txt"), "before");
    ResourceShare share = share("share-1", "notes.txt", SharedResourceType.FILE);
    share.setPermissions(Set.of(SharePermission.EDIT));

    when(shareRepository.findByIdAndRecipientIdAndRevokedAtIsNull("share-1", "recipient-1"))
        .thenReturn(Optional.of(share));

    String etag = new FileService().loadAsResource(target).getETag();

    Files.writeString(target, "changed-before-edit");

    assertThrows(
        ResourceConflictException.class,
        () ->
            service.editFile(
                "share-1",
                recipient,
                "",
                new MockMultipartFile("file", "replacement".getBytes()),
                etag));

    assertEquals("changed-before-edit", Files.readString(target));
  }

  @Test
  void editSucceedsWithCurrentETag() throws Exception {
    Path target = Files.writeString(ownerRoot.resolve("notes.txt"), "before");
    ResourceShare share = share("share-1", "notes.txt", SharedResourceType.FILE);
    share.setPermissions(Set.of(SharePermission.EDIT));

    when(shareRepository.findByIdAndRecipientIdAndRevokedAtIsNull("share-1", "recipient-1"))
        .thenReturn(Optional.of(share));

    String etag = new FileService().loadAsResource(target).getETag();

    service.editFile(
        "share-1", recipient, "", new MockMultipartFile("file", "replacement".getBytes()), etag);

    assertEquals("replacement", Files.readString(target));
  }

  @Test
  void editRespectsOwnerStorageQuota() throws Exception {
    Path target = Files.writeString(ownerRoot.resolve("notes.txt"), "before");
    owner.setStorageQuotaMb(0L);
    ResourceShare share = share("share-1", "notes.txt", SharedResourceType.FILE);
    share.setPermissions(Set.of(SharePermission.EDIT));
    when(shareRepository.findByIdAndRecipientIdAndRevokedAtIsNull("share-1", "recipient-1"))
        .thenReturn(Optional.of(share));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.editFile(
                "share-1", recipient, "", new MockMultipartFile("file", "replacement".getBytes())));
    assertEquals("before", Files.readString(target));
  }

  @Test
  void folderShareAllowsNestedFileButCannotEscapeSharedFolder() throws Exception {
    Path project = Files.createDirectory(ownerRoot.resolve("project"));
    Path nested = Files.createDirectory(project.resolve("nested"));
    Path target = Files.writeString(nested.resolve("notes.txt"), "notes");
    Files.writeString(ownerRoot.resolve("private.txt"), "private");
    ResourceShare share = share("share-1", "project", SharedResourceType.FOLDER);
    when(shareRepository.findByIdAndRecipientIdAndRevokedAtIsNull("share-1", "recipient-1"))
        .thenReturn(Optional.of(share));

    assertEquals(
        target.toRealPath(),
        service
            .resolveFile("share-1", recipient, "nested/notes.txt", SharePermission.DOWNLOAD)
            .path());
    assertThrows(
        ResourceNotFoundException.class,
        () ->
            service.resolveFile("share-1", recipient, "../private.txt", SharePermission.DOWNLOAD));
  }

  @Test
  void revokedShareStopsAccessImmediately() throws Exception {
    Files.writeString(ownerRoot.resolve("shared.txt"), "data");
    ResourceShare share = share("share-1", "shared.txt", SharedResourceType.FILE);
    when(shareRepository.findByIdAndOwnerId("share-1", "owner-1")).thenReturn(Optional.of(share));
    when(shareRepository.findByIdAndRecipientIdAndRevokedAtIsNull("share-1", "recipient-1"))
        .thenReturn(Optional.of(share))
        .thenReturn(Optional.empty());

    service.resolveFile("share-1", recipient, "", SharePermission.DOWNLOAD);
    service.revoke("owner-1", "share-1");

    assertEquals(NOW, share.getRevokedAt());
    assertThrows(
        ResourceNotFoundException.class,
        () -> service.resolveFile("share-1", recipient, "", SharePermission.DOWNLOAD));
  }

  @Test
  void sharedResourceItselfCannotBeDeletedOrRenamedThroughChildOperations() throws Exception {
    Path project = Files.createDirectory(ownerRoot.resolve("project"));
    Path nested = Files.createDirectory(project.resolve("nested"));
    Files.writeString(nested.resolve("notes.txt"), "notes");
    ResourceShare share = share("share-1", "project", SharedResourceType.FOLDER);
    share.setPermissions(
        Set.of(SharePermission.VIEW, SharePermission.DOWNLOAD, SharePermission.EDIT));
    when(shareRepository.findByIdAndRecipientIdAndRevokedAtIsNull("share-1", "recipient-1"))
        .thenReturn(Optional.of(share));

    // Every spelling that normalises back to the share root, not just the empty one.
    for (String selfPath : new String[] {"", ".", "nested/.."}) {
      assertThrows(
          ShareAccessDeniedException.class,
          () -> service.deleteInShare("share-1", recipient, selfPath));
      assertThrows(
          ShareAccessDeniedException.class,
          () -> service.renameInShare("share-1", recipient, selfPath, "renamed"));
    }
    assertTrue(Files.isDirectory(project), "the owner's shared folder must survive");

    // The guard must not block ordinary child operations.
    service.deleteInShare("share-1", recipient, "nested/notes.txt");
    assertTrue(Files.notExists(nested.resolve("notes.txt")));
  }

  /**
   * Regression test for the bug: deleting a file inside a share used to hard-delete it via {@code
   * fileService.deleteFile}, bypassing the owner's trash entirely and leaving the owner with no way
   * to recover a file an EDIT-permission recipient deleted.
   */
  @Test
  void deletingAFileInsideAShareMovesItToTheOwnersTrashInsteadOfHardDeleting() throws Exception {
    Path project = Files.createDirectory(ownerRoot.resolve("project"));
    Files.writeString(project.resolve("report.pdf"), "report");

    ResourceShare share = share("share-1", "project", SharedResourceType.FOLDER);
    share.setPermissions(Set.of(SharePermission.VIEW, SharePermission.EDIT));

    when(shareRepository.findByIdAndRecipientIdAndRevokedAtIsNull("share-1", "recipient-1"))
        .thenReturn(Optional.of(share));

    service.deleteInShare("share-1", recipient, "report.pdf");

    assertTrue(Files.notExists(project.resolve("report.pdf")), "gone from its original path");

    Path trashDir = ownerRoot.resolve(".trash");
    assertTrue(Files.isDirectory(trashDir), "a trash directory must have been created");

    try (var trashedFiles = Files.list(trashDir)) {
      assertEquals(1, trashedFiles.count(), "the file must exist somewhere recoverable in trash");
    }

    verify(trashEntryRepository, times(1))
        .save(
            org.mockito.ArgumentMatchers.argThat(
                entry ->
                    entry.getUserId().equals("owner-1")
                        && entry
                            .getOriginalPath()
                            .replace('\\', '/')
                            .equals("project/report.pdf")));
  }

  /**
   * Documents current, intentional behavior rather than a gap this fix introduces: folders have no
   * trash mechanism anywhere in this codebase yet (an owner's own folder delete via
   * FolderController hard-deletes too), so deleting a folder inside a share still hard-deletes,
   * consistent with what the owner's own delete does today. Building folder-level trash is
   * separate, larger follow-up work, not something this fix silently expands into.
   */
  @Test
  void deletingAFolderInsideAShareStillHardDeletes_matchingCurrentOwnerBehavior() throws Exception {
    Path project = Files.createDirectory(ownerRoot.resolve("project"));
    Files.createDirectory(project.resolve("subfolder"));
    ResourceShare share = share("share-1", "project", SharedResourceType.FOLDER);
    share.setPermissions(Set.of(SharePermission.VIEW, SharePermission.EDIT));
    when(shareRepository.findByIdAndRecipientIdAndRevokedAtIsNull("share-1", "recipient-1"))
        .thenReturn(Optional.of(share));

    service.deleteInShare("share-1", recipient, "subfolder");

    assertTrue(Files.notExists(project.resolve("subfolder")));
    assertTrue(
        Files.notExists(ownerRoot.resolve(".trash")),
        "no trash entry should be created for a folder — that mechanism does not exist yet");
    verify(trashEntryRepository, times(0)).save(any());
  }

  @Test
  void newNamesInsideAShareMustBeASingleComponent() throws Exception {
    Path project = Files.createDirectory(ownerRoot.resolve("project"));
    ResourceShare share = share("share-1", "project", SharedResourceType.FOLDER);
    share.setPermissions(Set.of(SharePermission.VIEW, SharePermission.EDIT));
    when(shareRepository.findByIdAndRecipientIdAndRevokedAtIsNull("share-1", "recipient-1"))
        .thenReturn(Optional.of(share));

    // A name is joined onto a directory inside the share; separators would place it
    // outside the share while still inside the owner's root, where FolderService
    // would happily create it.
    for (String escaping : new String[] {"../escape", "nested/deep", "..", "."}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> service.createFolderInShare("share-1", recipient, "", escaping));
    }
    assertTrue(Files.notExists(ownerRoot.resolve("escape")));

    // Dots inside a name are legitimate and must still be accepted.
    service.createFolderInShare("share-1", recipient, "", "notes..v2");
    assertTrue(Files.isDirectory(project.resolve("notes..v2")));
  }

  @Test
  void receivedListHidesSharesWhoseTargetIsGoneAndLooksEachOwnerUpOnce() throws Exception {
    Files.writeString(ownerRoot.resolve("kept.txt"), "kept");
    ResourceShare kept = share("share-kept", "kept.txt", SharedResourceType.FILE);
    ResourceShare removed = share("share-removed", "deleted.txt", SharedResourceType.FILE);
    when(shareRepository.findByRecipientIdAndRevokedAtIsNullOrderByCreatedAtDesc("recipient-1"))
        .thenReturn(List.of(kept, removed));

    var received = service.listReceived(recipient);

    assertEquals(1, received.size());
    assertEquals("share-kept", received.get(0).id());
    // Two shares from the same owner must not cost four user lookups.
    verify(userRepository, times(1)).findById("owner-1");
  }

  private ResourceShare share(String id, String path, SharedResourceType type) {
    ResourceShare share = new ResourceShare();
    share.setId(id);
    share.setOwnerId("owner-1");
    share.setRecipientId("recipient-1");
    share.setRelativePath(path);
    share.setDisplayName(Path.of(path).getFileName().toString());
    share.setResourceType(type);
    share.setPermissions(Set.of(SharePermission.VIEW, SharePermission.DOWNLOAD));
    share.setCreatedAt(NOW);
    return share;
  }

  private User user(String id, String username, Path root) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    user.setRootFolderPath(root.toString());
    return user;
  }
}
