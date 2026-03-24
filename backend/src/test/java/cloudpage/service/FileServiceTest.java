package cloudpage.service;

import static org.junit.jupiter.api.Assertions.*;

import cloudpage.dto.FileResource;
import cloudpage.exceptions.FileNotFoundException;
import cloudpage.exceptions.InvalidPathException;
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

    fileService.uploadFile(tempDir.toString(), "docs", file);

    Path uploaded = tempDir.resolve("docs/hello.txt");
    assertTrue(Files.exists(uploaded));
    assertEquals("Hello World", Files.readString(uploaded));
  }

  @Test
  void uploadFile_createsDirectoryIfNotExists() throws IOException {
    MockMultipartFile file =
        new MockMultipartFile("file", "data.txt", "text/plain", "content".getBytes());

    fileService.uploadFile(tempDir.toString(), "newdir", file);

    assertTrue(Files.isDirectory(tempDir.resolve("newdir")));
    assertTrue(Files.exists(tempDir.resolve("newdir/data.txt")));
  }

  @Test
  void uploadFile_replacesExistingFile() throws IOException {
    Path dir = Files.createDirectory(tempDir.resolve("docs"));
    Files.writeString(dir.resolve("file.txt"), "old content");

    MockMultipartFile file =
        new MockMultipartFile("file", "file.txt", "text/plain", "new content".getBytes());

    fileService.uploadFile(tempDir.toString(), "docs", file);

    assertEquals("new content", Files.readString(dir.resolve("file.txt")));
  }

  @Test
  void uploadFile_pathTraversal_throwsInvalidPathException() {
    MockMultipartFile file =
        new MockMultipartFile("file", "evil.txt", "text/plain", "hack".getBytes());

    assertThrows(
        InvalidPathException.class,
        () -> fileService.uploadFile(tempDir.toString(), "../../etc", file));
  }

  // ── deleteFile ───────────────────────────────────────────────────────────

  @Test
  void deleteFile_existingFile_deletesSuccessfully() throws IOException {
    Path file = Files.writeString(tempDir.resolve("toDelete.txt"), "bye");

    fileService.deleteFile(tempDir.toString(), "toDelete.txt");

    assertFalse(Files.exists(file));
  }

  @Test
  void deleteFile_nonExistentFile_doesNotThrow() {
    assertDoesNotThrow(() -> fileService.deleteFile(tempDir.toString(), "ghost.txt"));
  }

  @Test
  void deleteFile_pathTraversal_throwsInvalidPathException() {
    assertThrows(
        InvalidPathException.class,
        () -> fileService.deleteFile(tempDir.toString(), "../../etc/passwd"));
  }

  // ── renameOrMoveFile ─────────────────────────────────────────────────────

  @Test
  void renameOrMoveFile_renameInSameFolder() throws IOException {
    Files.writeString(tempDir.resolve("old.txt"), "data");

    fileService.renameOrMoveFile(tempDir.toString(), "old.txt", "new.txt");

    assertFalse(Files.exists(tempDir.resolve("old.txt")));
    assertTrue(Files.exists(tempDir.resolve("new.txt")));
    assertEquals("data", Files.readString(tempDir.resolve("new.txt")));
  }

  @Test
  void renameOrMoveFile_moveToDifferentFolder() throws IOException {
    Files.writeString(tempDir.resolve("moveme.txt"), "data");
    Files.createDirectory(tempDir.resolve("subfolder"));

    fileService.renameOrMoveFile(tempDir.toString(), "moveme.txt", "subfolder/moveme.txt");

    assertFalse(Files.exists(tempDir.resolve("moveme.txt")));
    assertTrue(Files.exists(tempDir.resolve("subfolder/moveme.txt")));
  }

  @Test
  void renameOrMoveFile_pathTraversal_throwsInvalidPathException() throws IOException {
    Files.writeString(tempDir.resolve("safe.txt"), "data");

    assertThrows(
        InvalidPathException.class,
        () -> fileService.renameOrMoveFile(tempDir.toString(), "safe.txt", "../../evil.txt"));
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

    // ETag format is "size-lastModifiedMillis"
    assertTrue(result.getETag().startsWith("\"5-"));
  }
}
