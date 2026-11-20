package cloudpage.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cloudpage.model.ResourceShare;
import cloudpage.model.SharePermission;
import cloudpage.model.SharedResourceType;
import cloudpage.model.TrashEntry;
import cloudpage.model.User;
import cloudpage.repository.ResourceShareRepository;
import cloudpage.repository.TrashEntryRepository;
import cloudpage.repository.UserRepository;
import cloudpage.util.FileUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class StorageQuotaConcurrencyTest {
  @TempDir Path root;
  private final FileService files = new FileService();
  private static final int UPLOAD_BYTES = 700 * 1024;

  @Test
  void anotherUsersUploadDoesNotWaitForBlockedRoot() throws Exception {
    Path other = Files.createDirectory(root.resolveSibling(root.getFileName() + "-other"));
    CountDownLatch writing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> files.uploadFile(root.toString(), "", pausedUpload(writing, release), 1L));
      try {
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        var second =
            executor.submit(() -> files.uploadFile(other.toString(), "", upload("other.bin"), 1L));
        second.get(5, TimeUnit.SECONDS);
        assertEquals(UPLOAD_BYTES, Files.size(other.resolve("other.bin")));
      } finally {
        release.countDown();
      }
      first.get(5, TimeUnit.SECONDS);
    } finally {
      Files.deleteIfExists(other.resolve("other.bin"));
      Files.delete(other);
    }
  }

  @Test
  void failedInputReleasesRootForAnotherThread() throws Exception {
    MockMultipartFile broken =
        new MockMultipartFile("file", "broken.bin", "application/octet-stream", new byte[1]) {
          @Override
          public InputStream getInputStream() throws IOException {
            throw new IOException("Synthetic read failure");
          }
        };
    assertThrows(IOException.class, () -> files.uploadFile(root.toString(), "", broken, 1L));
    try (var executor = Executors.newSingleThreadExecutor()) {
      executor
          .submit(() -> files.uploadFile(root.toString(), "", upload("retry.bin"), 1L))
          .get(5, TimeUnit.SECONDS);
    }
    assertFalse(Files.exists(root.resolve("broken.bin")));
    assertEquals(UPLOAD_BYTES, Files.size(root.resolve("retry.bin")));
  }

  @Test
  void concurrentUploadsToDifferentFoldersCannotExceedQuota() throws Exception {
    Files.createDirectories(root.resolve("one"));
    Files.createDirectories(root.resolve("two"));
    assertSerializedQuota(() -> files.uploadFile(root.toString(), "two", upload("second.bin"), 1L));
    assertFalse(Files.exists(root.resolve("two/second.bin")));
  }

  @Test
  void rootAliasesShareQuotaLock() throws Exception {
    Path alias = Files.createSymbolicLink(root.resolveSibling(root.getFileName() + "-alias"), root);
    try {
      assertSerializedQuota(() -> files.uploadFile(alias.toString(), "", upload("second.bin"), 1L));
      assertFalse(Files.exists(root.resolve("second.bin")));
    } finally {
      Files.delete(alias);
    }
  }

  @Test
  void sharedUploadUsesOwnersQuotaLock() throws Exception {
    ResourceShareService shares = shares();
    assertSerializedQuota(
        () -> shares.uploadToShare("share", recipient(), "", upload("shared.bin")));
    assertFalse(Files.exists(root.resolve("shared.bin")));
  }

  @Test
  void sharedReplacementUsesOwnersQuotaLock() throws Exception {
    Path existing = Files.write(root.resolve("existing.bin"), new byte[1]);
    ResourceShareService shares = shares();
    assertSerializedQuota(
        () -> shares.editFile("share", recipient(), "existing.bin", upload("existing.bin")));
    assertEquals(1, Files.size(existing));
  }

  @Test
  void trashRestoreUsesSameQuotaLock() throws Exception {
    TrashEntryRepository entries = mock(TrashEntryRepository.class);
    TrashService trash = new TrashService(entries, mock(UserRepository.class));
    Files.createDirectories(root.resolve(".trash"));
    Files.write(root.resolve(".trash/entry"), new byte[UPLOAD_BYTES]);
    TrashEntry entry = new TrashEntry();
    entry.setId("entry");
    entry.setOriginalPath("restored.bin");
    entry.setSizeBytes(UPLOAD_BYTES);
    when(entries.findByIdAndUserId("entry", "owner")).thenReturn(Optional.of(entry));
    assertSerializedQuota(() -> trash.restore(root.toString(), "owner", "entry", 1L));
    assertTrue(Files.exists(root.resolve(".trash/entry")));
    verify(entries, never()).delete(entry);
  }

  @Test
  void fileMoveWaitsForQuotaWrite() throws Exception {
    Files.write(root.resolve("before.bin"), new byte[1]);
    assertMutationWaits(() -> files.moveFile(root.toString(), "before.bin", "after.bin"));
    assertTrue(Files.exists(root.resolve("after.bin")));
  }

  @Test
  void folderMoveWaitsForQuotaWrite() throws Exception {
    Files.createDirectory(root.resolve("before"));
    Files.write(root.resolve("before/content.bin"), new byte[1]);
    assertMutationWaits(() -> new FolderService().moveFolder(root.toString(), "before", "after"));
    assertTrue(Files.exists(root.resolve("after/content.bin")));
  }

  @Test
  void failedTrashSaveCannotCompensateOverConcurrentUpload() throws Exception {
    Files.write(root.resolve("original.bin"), new byte[UPLOAD_BYTES]);
    TrashEntryRepository entries = mock(TrashEntryRepository.class);
    TrashService trash = new TrashService(entries, mock(UserRepository.class));
    CountDownLatch saving = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    when(entries.save(any()))
        .thenAnswer(
            invocation -> {
              saving.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              throw new IllegalStateException("Synthetic repository failure");
            });
    try (var executor = Executors.newFixedThreadPool(2)) {
      var deleting =
          executor.submit(
              () -> {
                trash.moveToTrash(root.toString(), "owner", "original.bin");
                return null;
              });
      try {
        assertTrue(saving.await(5, TimeUnit.SECONDS));
        var uploading =
            executor.submit(
                () -> {
                  started.countDown();
                  return files.uploadFile(root.toString(), "", upload("new.bin"), 1L);
                });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> uploading.get(300, TimeUnit.MILLISECONDS));
        release.countDown();
        assertInstanceOf(
            IllegalStateException.class,
            assertThrows(ExecutionException.class, () -> deleting.get(5, TimeUnit.SECONDS))
                .getCause());
        assertInstanceOf(
            IllegalArgumentException.class,
            assertThrows(ExecutionException.class, () -> uploading.get(5, TimeUnit.SECONDS))
                .getCause());
        assertEquals(UPLOAD_BYTES, Files.size(root.resolve("original.bin")));
        assertFalse(Files.exists(root.resolve("new.bin")));
      } finally {
        release.countDown();
      }
    }
  }

  private void assertMutationWaits(Write mutation) throws Exception {
    CountDownLatch writing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> files.uploadFile(root.toString(), "", pausedUpload(writing, release), 1L));
      try {
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        var second =
            executor.submit(
                () -> {
                  started.countDown();
                  mutation.run();
                  return null;
                });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    }
  }

  private void assertSerializedQuota(Write competing) throws Exception {
    long initialSize = FileUtils.calculateDirectorySize(root);
    CountDownLatch writing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> files.uploadFile(root.toString(), "one", pausedUpload(writing, release), 1L));
      try {
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        var second =
            executor.submit(
                () -> {
                  secondStarted.countDown();
                  competing.run();
                  return null;
                });
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        ExecutionException rejected =
            assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, rejected.getCause());
        assertEquals(initialSize + UPLOAD_BYTES, FileUtils.calculateDirectorySize(root));
      } finally {
        release.countDown();
      }
    }
  }

  private MockMultipartFile pausedUpload(CountDownLatch writing, CountDownLatch release) {
    return new MockMultipartFile(
        "file", "first.bin", "application/octet-stream", new byte[UPLOAD_BYTES]) {
      @Override
      public InputStream getInputStream() throws IOException {
        writing.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS))
            throw new IOException("Timed out waiting for test release");
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IOException(exception);
        }
        return super.getInputStream();
      }
    };
  }

  private MockMultipartFile upload(String name) {
    return new MockMultipartFile("file", name, "application/octet-stream", new byte[UPLOAD_BYTES]);
  }

  @FunctionalInterface
  private interface Write {
    void run() throws Exception;
  }

  private User recipient() {
    User user = new User();
    user.setId("recipient");
    return user;
  }

  private ResourceShareService shares() {
    User owner = new User();
    owner.setId("owner");
    owner.setRootFolderPath(root.toString());
    owner.setStorageQuotaMb(1L);
    UserRepository users = mock(UserRepository.class);
    when(users.findById("owner")).thenReturn(Optional.of(owner));
    ResourceShare share = new ResourceShare();
    share.setId("share");
    share.setOwnerId("owner");
    share.setRecipientId("recipient");
    share.setRelativePath("");
    share.setResourceType(SharedResourceType.FOLDER);
    share.setPermissions(Set.of(SharePermission.EDIT));
    ResourceShareRepository repository = mock(ResourceShareRepository.class);
    when(repository.findByIdAndRecipientIdAndRevokedAtIsNull("share", "recipient"))
        .thenReturn(Optional.of(share));
    return new ResourceShareService(
        repository,
        users,
        new FolderService(),
        files,
        new TrashService(mock(TrashEntryRepository.class), users));
  }
}
