package cloudpage.service;

import static org.junit.jupiter.api.Assertions.*;

import cloudpage.dto.FileResource;
import cloudpage.exceptions.FileNotFoundException;
import cloudpage.exceptions.InvalidPathException;
import cloudpage.exceptions.ResourceConflictException;
import cloudpage.exceptions.ResourceNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class FileServiceTest {

  private FileService fileService;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    fileService = new FileService();
  }

  // ── uploadFile ───────────────────────────────────────────────────────────

  @Test
  void uploadFile_normalUpload_createsFile() throws IOException {
    Files.createDirectory(tempDir.resolve("docs"));
    MockMultipartFile file =
        new MockMultipartFile("file", "hello.txt", "text/plain", "Hello World".getBytes());

    fileService.uploadFile(tempDir.toString(), "docs", file, null);

    Path uploaded = tempDir.resolve("docs/hello.txt");
    assertTrue(Files.exists(uploaded));
    assertEquals("Hello World", Files.readString(uploaded));
  }

  @Test
  void uploadFile_createsDirectoryIfNotExists() throws IOException {
    MockMultipartFile file =
        new MockMultipartFile("file", "data.txt", "text/plain", "content".getBytes());

    fileService.uploadFile(tempDir.toString(), "newdir", file, null);

    assertTrue(Files.isDirectory(tempDir.resolve("newdir")));
    assertTrue(Files.exists(tempDir.resolve("newdir/data.txt")));
  }

  @Test
  void uploadFile_replacesExistingFile() throws IOException {
    Path dir = Files.createDirectory(tempDir.resolve("docs"));
    Files.writeString(dir.resolve("file.txt"), "old content");

    MockMultipartFile file =
        new MockMultipartFile("file", "file.txt", "text/plain", "new content".getBytes());

    fileService.uploadFile(tempDir.toString(), "docs", file, null);

    assertEquals("new content", Files.readString(dir.resolve("file.txt")));
  }

  @Test
  void uploadFile_currentETag_succeeds() throws Exception {
    Path target = Files.writeString(tempDir.resolve("file.txt"), "old content");
    String currentETag = fileService.loadAsResource(target).getETag();

    MockMultipartFile file =
        new MockMultipartFile("file", "file.txt", "text/plain", "new content".getBytes());

    fileService.uploadFile(tempDir.toString(), "", file, null, currentETag);

    assertEquals("new content", Files.readString(target));
  }

  @Test
  void uploadFile_staleETag_throwsResourceConflictException() throws Exception {
    Path target = Files.writeString(tempDir.resolve("file.txt"), "old content");
    String staleETag = fileService.loadAsResource(target).getETag();

    Files.writeString(target, "changed content");

    MockMultipartFile file =
        new MockMultipartFile("file", "file.txt", "text/plain", "new content".getBytes());

    assertThrows(
        ResourceConflictException.class,
        () -> fileService.uploadFile(tempDir.toString(), "", file, null, staleETag));

    assertEquals("changed content", Files.readString(target));
  }

  @Test
  void uploadFile_expectedETag_targetMissing_throwsResourceConflictException() {
    MockMultipartFile file =
        new MockMultipartFile("file", "missing.txt", "text/plain", "new content".getBytes());

    assertThrows(
        ResourceConflictException.class,
        () -> fileService.uploadFile(tempDir.toString(), "", file, null, "\"missing-etag\""));
  }

  @Test
  void uploadFile_wildcardIfMatch_replacesExistingFile() throws Exception {
    Path target = Files.writeString(tempDir.resolve("file.txt"), "old content");
    MockMultipartFile file =
        new MockMultipartFile("file", "file.txt", "text/plain", "new content".getBytes());

    fileService.uploadFile(tempDir.toString(), "", file, null, "*");

    assertEquals("new content", Files.readString(target));
  }

  @Test
  void uploadFile_wildcardIfMatch_targetMissing_throwsResourceConflictException() {
    MockMultipartFile file =
        new MockMultipartFile("file", "missing.txt", "text/plain", "new content".getBytes());

    assertThrows(
        ResourceConflictException.class,
        () -> fileService.uploadFile(tempDir.toString(), "", file, null, "*"));
    assertFalse(Files.exists(tempDir.resolve("missing.txt")));
  }

  @Test
  void uploadFile_ifMatchListContainingCurrentETag_succeeds() throws Exception {
    Path target = Files.writeString(tempDir.resolve("file.txt"), "old content");
    String currentETag = fileService.loadAsResource(target).getETag();
    MockMultipartFile file =
        new MockMultipartFile("file", "file.txt", "text/plain", "new content".getBytes());

    fileService.uploadFile(tempDir.toString(), "", file, null, "\"other\", " + currentETag);

    assertEquals("new content", Files.readString(target));
  }

  @Test
  void uploadFile_weakIfMatch_throwsResourceConflictException() throws Exception {
    Path target = Files.writeString(tempDir.resolve("file.txt"), "old content");
    String currentETag = fileService.loadAsResource(target).getETag();
    MockMultipartFile file =
        new MockMultipartFile("file", "file.txt", "text/plain", "new content".getBytes());

    assertThrows(
        ResourceConflictException.class,
        () -> fileService.uploadFile(tempDir.toString(), "", file, null, "W/" + currentETag));
    assertEquals("old content", Files.readString(target));
  }

  @Test
  void uploadFile_replacingFile_countsOnlyTheSizeDifferenceAgainstQuota() throws Exception {
    // A 1 MB quota that is already full: replacing the file with one of equal size must still work.
    Path target = Files.write(tempDir.resolve("full.bin"), new byte[1024 * 1024]);
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "full.bin", "application/octet-stream", new byte[1024 * 1024]);

    assertDoesNotThrow(() -> fileService.uploadFile(tempDir.toString(), "", file, 1L));
    assertEquals(1024 * 1024, Files.size(target));
  }

  @Test
  void uploadFile_releasesEditLocksAfterwards() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "file.txt", "text/plain", "content".getBytes());

    fileService.uploadFile(tempDir.toString(), "", file, null);
    assertThrows(
        ResourceConflictException.class,
        () -> fileService.uploadFile(tempDir.toString(), "", file, null, "\"stale\""));

    assertEquals(0, fileService.activeEditLockCount());
  }

  @Test
  void editLockKey_resolvesSymlinkedFoldersToTheSameKey() throws Exception {
    Path realDir = Files.createDirectory(tempDir.resolve("real"));
    Path linkDir = Files.createSymbolicLink(tempDir.resolve("link"), realDir);

    assertEquals(
        FileService.editLockKey(realDir.resolve("file.txt")),
        FileService.editLockKey(linkDir.resolve("file.txt")));
  }

  @Test
  void uploadFile_pathTraversal_throwsInvalidPathException() {
    MockMultipartFile file =
        new MockMultipartFile("file", "evil.txt", "text/plain", "hack".getBytes());

    assertThrows(
        InvalidPathException.class,
        () -> fileService.uploadFile(tempDir.toString(), "../../etc", file, null));
  }

  @Test
  void uploadFile_filenameWithTraversal_staysWithinRoot() throws IOException {
    Path root = Files.createDirectory(tempDir.resolve("root"));
    MockMultipartFile file =
        new MockMultipartFile("file", "../../evil.txt", "text/plain", "hack".getBytes());

    fileService.uploadFile(root.toString(), "docs", file, null);

    // The crafted name is reduced to its final component and written inside the folder...
    assertTrue(Files.exists(root.resolve("docs/evil.txt")));
    // ...and nothing is written outside the user's root.
    assertFalse(Files.exists(tempDir.resolve("evil.txt")));
  }

  @Test
  void uploadFile_absolutePathFilename_staysWithinRoot() throws IOException {
    Path root = Files.createDirectory(tempDir.resolve("root"));
    Path outside = tempDir.resolve("outside.txt");
    MockMultipartFile file =
        new MockMultipartFile("file", outside.toString(), "text/plain", "hack".getBytes());

    fileService.uploadFile(root.toString(), "docs", file, null);

    assertFalse(Files.exists(outside));
    assertTrue(Files.exists(root.resolve("docs/outside.txt")));
  }

  @Test
  void uploadFile_blankFilename_throwsInvalidPathException() {
    MockMultipartFile file = new MockMultipartFile("file", "", "text/plain", "data".getBytes());

    assertThrows(
        InvalidPathException.class,
        () -> fileService.uploadFile(tempDir.toString(), "docs", file, null));
  }

  @Test
  void uploadFile_trashDestination_throwsInvalidPathException() {
    MockMultipartFile file =
        new MockMultipartFile("file", "hidden.txt", "text/plain", "data".getBytes());

    assertThrows(
        InvalidPathException.class,
        () -> fileService.uploadFile(tempDir.toString(), ".trash", file, null));

    assertFalse(Files.exists(tempDir.resolve(".trash")));
  }

  @Test
  void uploadFile_trashFilename_throwsInvalidPathException() {
    MockMultipartFile file =
        new MockMultipartFile("file", ".trash", "text/plain", "data".getBytes());

    assertThrows(
        InvalidPathException.class,
        () -> fileService.uploadFile(tempDir.toString(), "", file, null));

    assertFalse(Files.exists(tempDir.resolve(".trash")));
  }

  // ── renameOrMoveFile ─────────────────────────────────────────────────────

  @Test
  void renameOrMoveFile_renameInSameFolder() throws IOException {
    Files.writeString(tempDir.resolve("old.txt"), "data");

    fileService.moveFile(tempDir.toString(), "old.txt", "new.txt");

    assertFalse(Files.exists(tempDir.resolve("old.txt")));
    assertTrue(Files.exists(tempDir.resolve("new.txt")));
    assertEquals("data", Files.readString(tempDir.resolve("new.txt")));
  }

  @Test
  void moveToDifferentFolder() throws IOException {
    Files.writeString(tempDir.resolve("moveme.txt"), "data");
    Files.createDirectory(tempDir.resolve("subfolder"));

    fileService.moveFile(tempDir.toString(), "moveme.txt", "subfolder/moveme.txt");

    assertFalse(Files.exists(tempDir.resolve("moveme.txt")));
    assertTrue(Files.exists(tempDir.resolve("subfolder/moveme.txt")));
  }

  @Test
  void moveFile_pathTraversal_throwsInvalidPathException() throws IOException {
    Files.writeString(tempDir.resolve("safe.txt"), "data");

    assertThrows(
        InvalidPathException.class,
        () -> fileService.moveFile(tempDir.toString(), "safe.txt", "../../evil.txt"));
  }

  @Test
  void moveFile_trashDestination_throwsInvalidPathException() throws IOException {
    Path source = Files.writeString(tempDir.resolve("safe.txt"), "data");

    assertThrows(
        InvalidPathException.class,
        () -> fileService.moveFile(tempDir.toString(), "safe.txt", ".trash/safe.txt"));

    assertTrue(Files.exists(source));
    assertFalse(Files.exists(tempDir.resolve(".trash/safe.txt")));
  }

  @Test
  void moveFile_trashSource_throwsInvalidPathException() throws IOException {
    Path trash = Files.createDirectory(tempDir.resolve(".trash"));
    Path source = Files.writeString(trash.resolve("trashed-file"), "data");

    assertThrows(
        InvalidPathException.class,
        () -> fileService.moveFile(tempDir.toString(), ".trash/trashed-file", "restored.txt"));

    assertTrue(Files.exists(source));
    assertFalse(Files.exists(tempDir.resolve("restored.txt")));
  }

  // ── readFileContent ──────────────────────────────────────────────────────

  @Test
  void readFileContent_normalRead_returnsContent() throws IOException {
    Files.writeString(tempDir.resolve("readme.txt"), "Hello World");

    String content = fileService.readFileContent(tempDir.toString(), "readme.txt");

    assertEquals("Hello World", content);
  }

  @Test
  void readFileContent_fileNotFound_throwsResourceNotFoundException() {
    assertThrows(
        ResourceNotFoundException.class,
        () -> fileService.readFileContent(tempDir.toString(), "nonexistent.txt"));
  }

  @Test
  void readFileContent_directoryInsteadOfFile_throwsResourceNotFoundException() throws IOException {
    Files.createDirectory(tempDir.resolve("aFolder"));

    assertThrows(
        ResourceNotFoundException.class,
        () -> fileService.readFileContent(tempDir.toString(), "aFolder"));
  }

  @Test
  void readFileContent_pathTraversal_throwsInvalidPathException() {
    assertThrows(
        InvalidPathException.class,
        () -> fileService.readFileContent(tempDir.toString(), "../../etc/passwd"));
  }

  // ── loadAsResource ───────────────────────────────────────────────────────

  @Test
  void loadAsResource_existingFile_returnsResource() throws Exception {
    Path tempFile = Files.writeString(tempDir.resolve("test.txt"), "Hello");

    FileResource result = fileService.loadAsResource(tempFile);

    assertNotNull(result);
    assertNotNull(result.getResource());
    assertTrue(result.getResource().exists());
    assertNotNull(result.getETag());
    assertTrue(result.getLastModified() > 0);
  }

  @Test
  void loadAsResource_missingFile_throwsFileNotFoundException() {
    Path missing = tempDir.resolve("no-such-file.txt");

    assertThrows(FileNotFoundException.class, () -> fileService.loadAsResource(missing));
  }

  @Test
  void loadAsResource_directory_throwsFileNotFoundException() throws IOException {
    Path dir = Files.createDirectory(tempDir.resolve("somedir"));

    assertThrows(FileNotFoundException.class, () -> fileService.loadAsResource(dir));
  }

  @Test
  void loadAsResource_etagContainsFileSize() throws Exception {
    Path tempFile = Files.writeString(tempDir.resolve("sized.txt"), "12345");

    FileResource result = fileService.loadAsResource(tempFile);

    // ETag format is "size-lastModifiedNanos"
    assertTrue(result.getETag().startsWith("\"5-"));
  }

  // ── calculateChecksum ────────────────────────────────────────────────────

  @Test
  void calculateChecksum_knownContent_returnsExpectedSha256() throws IOException {
    Files.writeString(tempDir.resolve("hello.txt"), "Hello World");

    String checksum = fileService.calculateChecksum(tempDir.toString(), "hello.txt");

    // Well-known SHA-256 of the ASCII string "Hello World".
    assertEquals("a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e", checksum);
  }

  @Test
  void calculateChecksum_fileNotFound_throwsResourceNotFoundException() {
    assertThrows(
        ResourceNotFoundException.class,
        () -> fileService.calculateChecksum(tempDir.toString(), "ghost.txt"));
  }

  @Test
  void calculateChecksum_directory_throwsResourceNotFoundException() throws IOException {
    Files.createDirectory(tempDir.resolve("aFolder"));

    assertThrows(
        ResourceNotFoundException.class,
        () -> fileService.calculateChecksum(tempDir.toString(), "aFolder"));
  }

  @Test
  void calculateChecksum_pathTraversal_throwsInvalidPathException() {
    assertThrows(
        InvalidPathException.class,
        () -> fileService.calculateChecksum(tempDir.toString(), "../../etc/passwd"));
  }
}
