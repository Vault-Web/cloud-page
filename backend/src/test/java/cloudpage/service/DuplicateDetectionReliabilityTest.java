package cloudpage.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cloudpage.exceptions.ResourceConflictException;
import cloudpage.util.ChecksumUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class DuplicateDetectionReliabilityTest {
  @TempDir Path root;

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
