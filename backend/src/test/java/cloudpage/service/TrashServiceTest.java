package cloudpage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cloudpage.exceptions.ResourceNotFoundException;
import cloudpage.model.TrashEntry;
import cloudpage.model.User;
import cloudpage.repository.TrashEntryRepository;
import cloudpage.repository.UserRepository;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrashServiceTest {

  @Mock private TrashEntryRepository trashEntryRepository;
  @Mock private UserRepository userRepository;

  private TrashService trashService;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    trashService = new TrashService(trashEntryRepository, userRepository, new FolderService());
  }

  @Test
  void moveToTrash_movesFileIntoTrashAndSavesEntry() throws IOException {
    Files.writeString(tempDir.resolve("doc.txt"), "data");

    trashService.moveToTrash(tempDir.toString(), "user1", "doc.txt");

    assertFalse(Files.exists(tempDir.resolve("doc.txt")), "original file should be gone");
    Path trashDir = tempDir.resolve(TrashService.TRASH_DIR);
    assertTrue(Files.isDirectory(trashDir));
    try (var entries = Files.list(trashDir)) {
      assertEquals(1, entries.count(), "exactly one file should live in the trash");
    }

    ArgumentCaptor<TrashEntry> captor = ArgumentCaptor.forClass(TrashEntry.class);
    verify(trashEntryRepository).save(captor.capture());
    TrashEntry saved = captor.getValue();
    assertEquals("user1", saved.getUserId());
    assertEquals("doc.txt", saved.getOriginalPath());
    assertEquals("doc.txt", saved.getDisplayName());
    assertEquals(4, saved.getSizeBytes());
    assertNotNull(saved.getDeletedAt());
  }

  @Test
  void moveToTrash_nonExistentFile_throwsResourceNotFound() {
    assertThrows(
        ResourceNotFoundException.class,
        () -> trashService.moveToTrash(tempDir.toString(), "user1", "ghost.txt"));
    verifyNoInteractions(trashEntryRepository);
  }

  @Test
  void restore_movesFileBackToOriginalPathAndDeletesEntry() throws IOException {
    Path trashDir = Files.createDirectory(tempDir.resolve(TrashService.TRASH_DIR));
    Files.writeString(trashDir.resolve("abc"), "restored");

    TrashEntry entry = new TrashEntry();
    entry.setId("abc");
    entry.setUserId("user1");
    entry.setOriginalPath("sub/doc.txt");
    entry.setDisplayName("doc.txt");
    when(trashEntryRepository.findByIdAndUserId("abc", "user1")).thenReturn(Optional.of(entry));

    trashService.restore(tempDir.toString(), "user1", "abc");

    Path restored = tempDir.resolve("sub/doc.txt");
    assertTrue(Files.exists(restored), "file should be back at its original path");
    assertEquals("restored", Files.readString(restored));
    assertFalse(Files.exists(trashDir.resolve("abc")), "file should no longer be in the trash");
    verify(trashEntryRepository).delete(entry);
  }

  @Test
  void restore_unknownEntry_throwsResourceNotFound() {
    when(trashEntryRepository.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());
    assertThrows(
        ResourceNotFoundException.class,
        () -> trashService.restore(tempDir.toString(), "user1", "nope"));
  }

  @Test
  void restore_targetAlreadyExists_throwsAndKeepsExistingFile() throws IOException {
    Path trashDir = Files.createDirectory(tempDir.resolve(TrashService.TRASH_DIR));
    Files.writeString(trashDir.resolve("abc"), "trashed");
    // a file was recreated at the original path after deletion — restore must not overwrite it
    Files.writeString(tempDir.resolve("doc.txt"), "current");

    TrashEntry entry = new TrashEntry();
    entry.setId("abc");
    entry.setUserId("user1");
    entry.setOriginalPath("doc.txt");
    when(trashEntryRepository.findByIdAndUserId("abc", "user1")).thenReturn(Optional.of(entry));

    assertThrows(
        FileAlreadyExistsException.class,
        () -> trashService.restore(tempDir.toString(), "user1", "abc"));
    assertEquals("current", Files.readString(tempDir.resolve("doc.txt")));
    verify(trashEntryRepository, never()).delete(any());
  }

  @Test
  void purge_deletesTrashFileAndEntry() throws IOException {
    Path trashDir = Files.createDirectory(tempDir.resolve(TrashService.TRASH_DIR));
    Files.writeString(trashDir.resolve("xyz"), "bye");

    TrashEntry entry = new TrashEntry();
    entry.setId("xyz");
    entry.setUserId("user1");
    when(trashEntryRepository.findByIdAndUserId("xyz", "user1")).thenReturn(Optional.of(entry));

    trashService.purge(tempDir.toString(), "user1", "xyz");

    assertFalse(Files.exists(trashDir.resolve("xyz")));
    verify(trashEntryRepository).delete(entry);
  }

  @Test
  void purgeExpired_deletesExpiredFilesAndEntries() throws IOException {
    Path trashDir = Files.createDirectory(tempDir.resolve(TrashService.TRASH_DIR));
    Files.writeString(trashDir.resolve("old"), "stale");

    TrashEntry entry = new TrashEntry();
    entry.setId("old");
    entry.setUserId("user1");
    entry.setDeletedAt(Instant.now());

    User user = new User();
    user.setId("user1");
    user.setRootFolderPath(tempDir.toString());

    when(trashEntryRepository.findByDeletedAtBefore(any())).thenReturn(List.of(entry));
    when(userRepository.findById("user1")).thenReturn(Optional.of(user));

    trashService.purgeExpired();

    assertFalse(Files.exists(trashDir.resolve("old")), "expired trash file should be deleted");
    verify(trashEntryRepository).delete(entry);
  }
}
