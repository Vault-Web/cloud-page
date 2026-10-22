package cloudpage.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cloudpage.exceptions.ResourceConflictException;
import cloudpage.model.ResourceShare;
import cloudpage.model.SharePermission;
import cloudpage.model.SharedResourceType;
import cloudpage.model.User;
import cloudpage.repository.ResourceShareRepository;
import cloudpage.repository.UserRepository;
import cloudpage.util.ChecksumUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class DuplicateDetectionReliabilityTest {
  @TempDir Path root;

  @Test
  void metadataBudgetStopsTraversalBeforeHashing() throws IOException {
    Files.createDirectory(root.resolve("nested"));
    Files.writeString(root.resolve("nested/a.txt"), "same");
    try (var checksums = mockStatic(ChecksumUtils.class, CALLS_REAL_METHODS)) {
      assertTrue(
          new DuplicateDetector(1, 10, 100)
              .findDuplicates(root, root.resolve("upload.txt"), "unused", 4)
              .isEmpty());
      checksums.verifyNoInteractions();
    }
  }

  @Test
  void candidateBudgetIsSharedAcrossTheEntireListingScan() throws IOException {
    Files.writeString(root.resolve("a.txt"), "same");
    Files.writeString(root.resolve("b.txt"), "same");
    Files.writeString(root.resolve("c.txt"), "other");
    Files.writeString(root.resolve("d.txt"), "other");
    try (var checksums = mockStatic(ChecksumUtils.class, CALLS_REAL_METHODS)) {
      var page =
          new FolderService(new DuplicateDetector(100, 1, 100))
              .getFolderContentPage(root.toString(), "", 0, 10, null, true, true);
      assertTrue(page.getContent().stream().noneMatch(item -> item.isDuplicate()));
      checksums.verify(() -> ChecksumUtils.newDigest("SHA-256"), times(1));
    }
  }

  @Test
  void byteBudgetIsSharedAcrossCandidatesAndResetsForEachRequest() throws IOException {
    Files.writeString(root.resolve("a.txt"), "same");
    Files.writeString(root.resolve("b.txt"), "same");
    DuplicateDetector detector = new DuplicateDetector(100, 10, 4);
    String checksum =
        ChecksumUtils.calculateChecksum(new ByteArrayInputStream("same".getBytes()), "SHA-256");
    try (var checksums = mockStatic(ChecksumUtils.class, CALLS_REAL_METHODS)) {
      assertEquals(
          1, detector.findDuplicates(root, root.resolve("upload.txt"), checksum, 4).size());
      assertEquals(
          1, detector.findDuplicates(root, root.resolve("upload.txt"), checksum, 4).size());
      checksums.verify(() -> ChecksumUtils.newDigest("SHA-256"), times(2));
    }
  }

  @Test
  void candidateLargerThanRemainingBudgetIsNotRead() throws IOException {
    Files.writeString(root.resolve("a.txt"), "same");
    try (var checksums = mockStatic(ChecksumUtils.class, CALLS_REAL_METHODS)) {
      assertTrue(
          new DuplicateDetector(100, 10, 3)
              .findDuplicates(root, root.resolve("upload.txt"), "unused", 4)
              .isEmpty());
      checksums.verifyNoInteractions();
    }
  }

  @Test
  void growingCandidateCannotEscapeTheByteBudgetOrProduceAPartialMatch() throws IOException {
    Path candidate = Files.writeString(root.resolve("existing.txt"), "same");
    String checksum =
        ChecksumUtils.calculateChecksum(new ByteArrayInputStream("same".getBytes()), "SHA-256");
    int[] bytesRead = {0};
    boolean[] closed = {false};
    var growingInput =
        new java.io.FilterInputStream(Files.newInputStream(candidate)) {
          @Override
          public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
              bytesRead[0] += read;
              Files.writeString(candidate, "more", java.nio.file.StandardOpenOption.APPEND);
            }
            return read;
          }

          @Override
          public void close() throws IOException {
            closed[0] = true;
            super.close();
          }
        };
    try (growingInput;
        var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
      files
          .when(() -> Files.newInputStream(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS))
          .thenReturn(growingInput);
      assertTrue(
          new DuplicateDetector(100, 10, 4)
              .findDuplicates(root, root.resolve("upload.txt"), checksum, 4)
              .isEmpty());
      assertEquals(4, bytesRead[0]);
      assertTrue(closed[0]);
      assertEquals(8, Files.size(candidate));
    }
  }

  @Test
  void exhaustedDetectionBudgetStillReturnsASuccessfullySavedUpload() throws IOException {
    Files.writeString(root.resolve("existing.txt"), "saved");
    var result =
        new FileService(new DuplicateDetector(100, 10, 1))
            .uploadFile(root.toString(), "", file("saved"), null);
    assertEquals("saved", Files.readString(root.resolve("a.txt")));
    assertEquals("saved", Files.readString(root.resolve("existing.txt")));
    assertFalse(result.isDuplicate());
    assertTrue(result.getDuplicates().isEmpty());
  }

  @Test
  void sharedUploadDoesNotScanTheOwnersStorageForUnusedHints() throws IOException {
    DuplicateDetector detector = mock(DuplicateDetector.class);
    ResourceShareRepository shares = mock(ResourceShareRepository.class);
    UserRepository users = mock(UserRepository.class);
    User owner = new User();
    owner.setId("owner");
    owner.setRootFolderPath(root.toString());
    User recipient = new User();
    recipient.setId("recipient");
    ResourceShare share = new ResourceShare();
    share.setOwnerId("owner");
    share.setRecipientId("recipient");
    share.setRelativePath("docs");
    share.setResourceType(SharedResourceType.FOLDER);
    share.setPermissions(Set.of(SharePermission.EDIT));
    when(users.findById("owner")).thenReturn(Optional.of(owner));
    when(shares.findByIdAndRecipientIdAndRevokedAtIsNull("share", "recipient"))
        .thenReturn(Optional.of(share));
    Files.createDirectory(root.resolve("docs"));
    ResourceShareService service =
        new ResourceShareService(
            shares,
            users,
            new FolderService(),
            new FileService(detector),
            mock(TrashService.class));

    service.uploadToShare("share", recipient, "", file("saved"));

    assertEquals("saved", Files.readString(root.resolve("docs/a.txt")));
    verifyNoInteractions(detector);
  }

  @Test
  void ordinaryListingDoesNotStartADuplicateScan() throws IOException {
    DuplicateDetector detector = mock(DuplicateDetector.class);
    FolderService folders = new FolderService(detector);
    Files.writeString(root.resolve("a.txt"), "same");
    Files.writeString(root.resolve("b.txt"), "same");

    var page = folders.getFolderContentPage(root.toString(), "", 0, 10, null);

    assertEquals(2, page.getTotalElements());
    verifyNoInteractions(detector);
  }

  @Test
  void failedOptionalListingScanStillReturnsTheFolder() throws IOException {
    DuplicateDetector detector = mock(DuplicateDetector.class);
    doThrow(new UncheckedIOException(new IOException("unreadable directory")))
        .when(detector)
        .markDuplicates(any(), any(), anyList());
    Files.writeString(root.resolve("a.txt"), "same");

    var page =
        new FolderService(detector)
            .getFolderContentPage(root.toString(), "", 0, 10, null, true, true);

    assertEquals("a.txt", page.getContent().getFirst().getName());
    assertFalse(page.getContent().getFirst().isDuplicate());
  }

  @Test
  void checkedScanFailureDoesNotFailAnAlreadySavedUpload() throws IOException {
    successfulUploadDespiteScanFailure(new IOException("unreadable tree"));
  }

  @Test
  void uncheckedScanFailureDoesNotFailAnAlreadySavedUpload() throws IOException {
    successfulUploadDespiteScanFailure(
        new UncheckedIOException(new IOException("unreadable tree")));
  }

  private void successfulUploadDespiteScanFailure(Exception failure) throws IOException {
    DuplicateDetector detector = mock(DuplicateDetector.class);
    when(detector.findDuplicates(any(), any(), anyString(), anyLong()))
        .thenAnswer(
            call -> {
              assertEquals("saved", Files.readString(root.resolve("a.txt")));
              throw failure;
            });

    var result = new FileService(detector).uploadFile(root.toString(), "", file("saved"), null);

    assertEquals("saved", Files.readString(root.resolve("a.txt")));
    assertFalse(result.isDuplicate());
    assertTrue(result.getDuplicates().isEmpty());
  }

  @Test
  void rejectedPreconditionDoesNotStartAScan() throws IOException {
    DuplicateDetector detector = mock(DuplicateDetector.class);
    Files.writeString(root.resolve("a.txt"), "old");
    assertThrows(
        ResourceConflictException.class,
        () ->
            new FileService(detector)
                .uploadFile(root.toString(), "", file("new"), null, "\"stale\""));
    verifyNoInteractions(detector);
    assertEquals("old", Files.readString(root.resolve("a.txt")));
  }

  @Test
  void rejectedQuotaDoesNotStartAScan() {
    DuplicateDetector detector = mock(DuplicateDetector.class);
    assertThrows(
        IllegalArgumentException.class,
        () -> new FileService(detector).uploadFile(root.toString(), "", file("new"), 0L));
    verifyNoInteractions(detector);
    assertFalse(Files.exists(root.resolve("a.txt")));
  }

  @Test
  void uploadClosesItsInputAndPassesTheIncomingBytesChecksum() throws IOException {
    DuplicateDetector detector = mock(DuplicateDetector.class);
    when(detector.findDuplicates(any(), any(), anyString(), anyLong())).thenReturn(List.of());
    boolean[] closed = {false};
    MockMultipartFile upload =
        new MockMultipartFile("file", "a.txt", "text/plain", "abc".getBytes()) {
          @Override
          public java.io.InputStream getInputStream() {
            return new ByteArrayInputStream("abc".getBytes()) {
              @Override
              public void close() throws IOException {
                closed[0] = true;
                super.close();
              }
            };
          }
        };
    new FileService(detector).uploadFile(root.toString(), "", upload, null);

    assertTrue(closed[0]);
    verify(detector)
        .findDuplicates(
            eq(root),
            eq(root.resolve("a.txt")),
            eq("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            eq(3L));
  }

  @Test
  void listingImmediatelyReflectsCreateDeleteOverwriteAndTrash() throws IOException {
    FolderService folders = new FolderService();
    Path first = Files.writeString(root.resolve("a.txt"), "same");
    assertFalse(duplicate(folders, "a.txt"));
    Path second = Files.writeString(root.resolve("b.txt"), "same");
    assertTrue(duplicate(folders, "a.txt"));
    Files.delete(second);
    assertFalse(duplicate(folders, "a.txt"));
    Files.writeString(second, "same");
    assertTrue(duplicate(folders, "a.txt"));
    FileTime previousTime = Files.getLastModifiedTime(second);
    Files.writeString(second, "diff");
    Files.setLastModifiedTime(second, previousTime);
    assertFalse(duplicate(folders, "a.txt"));
    Files.writeString(second, "same");
    Files.createDirectory(root.resolve(".trash"));
    Files.move(second, root.resolve(".trash/b.txt"));
    assertFalse(duplicate(folders, "a.txt"));
    assertEquals("same", Files.readString(first));
  }

  @Test
  void uniqueSizesDoNotReadAnyFileContentsForDuplicateBadges() throws IOException {
    Files.writeString(root.resolve("a.txt"), "a");
    Files.writeString(root.resolve("b.txt"), "bb");
    try (var checksums = mockStatic(ChecksumUtils.class, CALLS_REAL_METHODS)) {
      new FolderService().getFolderContentPage(root.toString(), "", 0, 10, null, true, true);
      checksums.verifyNoInteractions();
    }
  }

  @Test
  void trashAndSymlinkTargetsDoNotCreateDuplicateHints() throws IOException {
    Path outside = Files.createTempFile("duplicate-outside", ".txt");
    try {
      Files.writeString(outside, "saved");
      Files.createDirectory(root.resolve(".trash"));
      Files.writeString(root.resolve(".trash/deleted.txt"), "saved");
      link(root.resolve("alias.txt"), outside);
      var result = new FileService().uploadFile(root.toString(), "", file("saved"), null);
      assertFalse(result.isDuplicate());
      assertFalse(duplicate(new FolderService(), "a.txt"));
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void symlinkedRootDoesNotReportTheUploadedFileAsItsOwnDuplicate() throws IOException {
    Path container = Files.createTempDirectory("duplicate-root-alias");
    Path alias = container.resolve("alias");
    try {
      link(alias, root);
      var result = new FileService().uploadFile(alias.toString(), "", file("saved"), null);
      assertFalse(result.isDuplicate());
    } finally {
      Files.deleteIfExists(alias);
      Files.deleteIfExists(container);
    }
  }

  private boolean duplicate(FolderService folders, String name) throws IOException {
    return folders
        .getFolderContentPage(root.toString(), "", 0, 20, null, true, true)
        .getContent()
        .stream()
        .filter(item -> item.getName().equals(name))
        .findFirst()
        .orElseThrow()
        .isDuplicate();
  }

  private static MockMultipartFile file(String content) {
    return new MockMultipartFile(
        "file", "a.txt", "text/plain", content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static void link(Path alias, Path target) throws IOException {
    try {
      Files.createSymbolicLink(alias, target);
    } catch (IOException | UnsupportedOperationException exception) {
      assumeTrue(false, "Symbolic links unavailable: " + exception.getMessage());
    }
  }
}
