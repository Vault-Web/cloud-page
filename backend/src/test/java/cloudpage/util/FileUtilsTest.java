package cloudpage.util;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloudpage.exceptions.InvalidPathException;
import cloudpage.exceptions.ResourceAlreadyExistsException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FileUtilsTest {

  @TempDir Path tempDir;

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void move_occupiedDestination_preservesBothEntries(boolean sourceFolder, boolean targetFolder)
      throws IOException {
    Path source = tempDir.resolve("source");
    Path target = tempDir.resolve("target");
    Path sourceData = sourceFolder ? Files.createDirectory(source).resolve("data.txt") : source;
    Path targetData = targetFolder ? Files.createDirectory(target).resolve("data.txt") : target;
    Files.writeString(sourceData, "source bytes");
    Files.writeString(targetData, "destination bytes");

    assertThrows(
        ResourceAlreadyExistsException.class,
        () -> FileUtils.move(tempDir.toString(), "./source", "unused/../target"));

    assertEquals("source bytes", Files.readString(sourceData));
    assertEquals("destination bytes", Files.readString(targetData));
    assertFalse(Files.exists(tempDir.resolve(".trash")));
  }

  @Test
  void move_emptyDestinationFolder_isNotReplaced() throws IOException {
    Path source = Files.createDirectory(tempDir.resolve("source"));
    Files.writeString(source.resolve("data.txt"), "source bytes");
    Path target = Files.createDirectory(tempDir.resolve("target"));

    assertThrows(
        ResourceAlreadyExistsException.class,
        () -> FileUtils.move(tempDir.toString(), "source", "target"));

    assertEquals("source bytes", Files.readString(source.resolve("data.txt")));
    try (var children = Files.list(target)) {
      assertEquals(0, children.count());
    }
  }

  @Test
  void move_danglingDestinationSymlink_isNotReplaced() throws IOException {
    Path source = Files.writeString(tempDir.resolve("source"), "source bytes");
    Path missing = tempDir.resolve("missing");
    Path target = Files.createSymbolicLink(tempDir.resolve("target"), missing);

    assertThrows(
        ResourceAlreadyExistsException.class,
        () -> FileUtils.move(tempDir.toString(), "source", "target"));

    assertEquals("source bytes", Files.readString(source));
    assertTrue(Files.isSymbolicLink(target));
    assertEquals(missing, Files.readSymbolicLink(target));
  }

  @Test
  void move_normalizedSamePath_preservesFile() throws IOException {
    Path source = Files.writeString(tempDir.resolve("source"), "source bytes");

    FileUtils.move(tempDir.toString(), "source", "./unused/../source");

    assertEquals("source bytes", Files.readString(source));
  }

  @Test
  void test_resolveExistingFilePath_inRootDirectory_ok() throws IOException {
    Path pathToResolve = tempDir.resolve("testpath");
    Files.createFile(pathToResolve);

    assertThatCode(
            () -> FileUtils.validateAndResolvePathWithinRoot(tempDir.toString(), pathToResolve))
        .as("A path inside the root path should be validated successfully")
        .doesNotThrowAnyException();

    assertThatCode(() -> FileUtils.validateAndResolvePathWithinRoot(tempDir, pathToResolve))
        .as("A path inside the root path should be validated successfully")
        .doesNotThrowAnyException();
  }

  @Test
  void test_resolveExistingFilePath_notInRootDirectory_fail() throws IOException {
    Path rootDir = tempDir.resolve("testpath");
    Files.createDirectory(rootDir);

    Path pathToResolve = tempDir.resolve("otherpath");
    Files.createFile(pathToResolve);

    assertThatCode(
            () -> FileUtils.validateAndResolvePathWithinRoot(rootDir.toString(), pathToResolve))
        .as("A path outside the root path should not be validated successfully")
        .isInstanceOf(InvalidPathException.class)
        .hasMessage("Path traversal attempt detected: " + pathToResolve);

    assertThatCode(() -> FileUtils.validateAndResolvePathWithinRoot(rootDir, pathToResolve))
        .as("A path outside the root path should not be validated successfully")
        .isInstanceOf(InvalidPathException.class)
        .hasMessage("Path traversal attempt detected: " + pathToResolve);
  }

  @Test
  void validatePath_validPath_doesNotThrow() {
    Path valid = tempDir.resolve("somefile.txt");

    assertDoesNotThrow(() -> FileUtils.validatePath(tempDir.toString(), valid));
  }

  @Test
  void resolveActivePathKeepsTrashOutsideOrdinaryFileAccess() throws IOException {
    Path activeFile = Files.createFile(tempDir.resolve("document.txt"));
    Path trash = Files.createDirectory(tempDir.resolve(FileUtils.TRASH_DIR));

    assertEquals(
        activeFile.toRealPath(), FileUtils.resolveActivePath(tempDir.toString(), "document.txt"));
    assertThrows(
        InvalidPathException.class,
        () -> FileUtils.resolveActivePath(tempDir.toString(), trash.getFileName().toString()));
  }

  @Test
  void validatePath_outsideRoot_throwsInvalidPathException() {
    Path outside = tempDir.resolve("../../etc/passwd").normalize();

    assertThrows(
        InvalidPathException.class, () -> FileUtils.validatePath(tempDir.toString(), outside));
  }

  @Test
  void test_resolveFilePath_usePathTraversalAttempt_fail() throws IOException {
    Path rootDir = tempDir.resolve("testpath");
    Files.createDirectory(rootDir);

    Path pathToResolve = Paths.get(rootDir.toString(), "../");

    assertThatCode(
            () -> FileUtils.validateAndResolvePathWithinRoot(rootDir.toString(), pathToResolve))
        .as("A path that uses path traversal should not be validated successfully")
        .isInstanceOf(InvalidPathException.class)
        .hasMessage("Path traversal attempt detected: " + pathToResolve);

    assertThatCode(() -> FileUtils.validateAndResolvePathWithinRoot(rootDir, pathToResolve))
        .as("A path that uses path traversal should not be validated successfully")
        .isInstanceOf(InvalidPathException.class)
        .hasMessage("Path traversal attempt detected: " + pathToResolve);
  }

  @Test
  void test_resolveFilePath_usePathTraversalAttemptToResolveDifferentDir_fail() throws IOException {
    Path rootDir = tempDir.resolve("testpath");
    Files.createDirectory(rootDir);

    Path pathToResolve = Paths.get(rootDir.toString(), "../differentPath");

    assertThatCode(
            () -> FileUtils.validateAndResolvePathWithinRoot(rootDir.toString(), pathToResolve))
        .as("A path that uses path traversal should not be validated successfully")
        .isInstanceOf(InvalidPathException.class)
        .hasMessage("Path traversal attempt detected: " + pathToResolve);

    assertThatCode(() -> FileUtils.validateAndResolvePathWithinRoot(rootDir, pathToResolve))
        .as("A path that uses path traversal should not be validated successfully")
        .isInstanceOf(InvalidPathException.class)
        .hasMessage("Path traversal attempt detected: " + pathToResolve);
  }

  @Test
  void test_resolveFilePath_useSymlinks_fail() throws IOException {
    Path rootDir = tempDir.resolve("testpath");
    Files.createDirectory(rootDir);

    Path pathToResolve = Paths.get(rootDir.toString(), "symbolic");
    Files.createSymbolicLink(pathToResolve, Paths.get("/etc/passwd"));

    assertThatCode(
            () -> FileUtils.validateAndResolvePathWithinRoot(rootDir.toString(), pathToResolve))
        .as("A path that uses path traversal should not be validated successfully")
        .isInstanceOf(InvalidPathException.class)
        .hasMessage("Path traversal attempt detected: " + pathToResolve);

    assertThatCode(() -> FileUtils.validateAndResolvePathWithinRoot(rootDir, pathToResolve))
        .as("A path that uses path traversal should not be validated successfully")
        .isInstanceOf(InvalidPathException.class)
        .hasMessage("Path traversal attempt detected: " + pathToResolve);
  }

  // ── validateAdditionalStorageWithinQuota ────────────────────────────────

  @Test
  void validateAdditionalStorageWithinQuota_exceedsQuota_throwsIllegalArgumentException()
      throws IOException {
    Files.write(tempDir.resolve("existing.txt"), new byte[1024 * 1024]);

    assertThrows(
        IllegalArgumentException.class,
        () -> FileUtils.validateStorageSizeWithinQuota(tempDir.toString(), 1, 1L));
  }

  @Test
  void validateAdditionalStorageWithinQuota_atQuotaLimit_doesNotThrow() throws IOException {
    Files.write(tempDir.resolve("existing.txt"), new byte[1024 * 1024 - 1]);

    assertDoesNotThrow(() -> FileUtils.validateStorageSizeWithinQuota(tempDir.toString(), 1, 1L));
  }

  @Test
  void validateAdditionalStorageWithinQuota_ignoresTrashFiles() throws IOException {
    Path trash = Files.createDirectory(tempDir.resolve(".trash"));
    Files.write(trash.resolve("deleted.txt"), new byte[1024 * 1024]);

    assertDoesNotThrow(() -> FileUtils.validateStorageSizeWithinQuota(tempDir.toString(), 1, 1L));
  }

  // ── replaceAtomically ────────────────────────────────────────────────────

  @Test
  void replaceAtomically_replacesExistingContent() throws IOException {
    Path target = Files.writeString(tempDir.resolve("notes.txt"), "before");

    FileUtils.replaceAtomically(target, new ByteArrayInputStream("after".getBytes()));

    assertEquals("after", Files.readString(target));
    assertEquals(1, directoryEntries(), "no temporary file may be left behind");
  }

  @Test
  void replaceAtomically_createsMissingTarget() throws IOException {
    Path target = tempDir.resolve("new.txt");

    FileUtils.replaceAtomically(target, new ByteArrayInputStream("fresh".getBytes()));

    assertEquals("fresh", Files.readString(target));
    assertEquals(1, directoryEntries(), "no temporary file may be left behind");
  }

  @Test
  void replaceAtomically_failureMidWrite_keepsOriginalContent() throws IOException {
    Path target = Files.writeString(tempDir.resolve("notes.txt"), "original");

    assertThrows(
        IOException.class,
        () -> FileUtils.replaceAtomically(target, new FailingInputStream("partial-new-bytes")));

    assertEquals("original", Files.readString(target));
    assertEquals(1, directoryEntries(), "the failed write must not leave a temporary file");
  }

  @Test
  void replaceAtomically_failureMidWrite_doesNotCreateMissingTarget() throws IOException {
    Path target = tempDir.resolve("new.txt");

    assertThrows(
        IOException.class,
        () -> FileUtils.replaceAtomically(target, new FailingInputStream("partial")));

    assertFalse(Files.exists(target));
    assertEquals(0, directoryEntries(), "the failed write must not leave a temporary file");
  }

  @Test
  void replaceAtomically_keepsPermissionsOfReplacedFile() throws IOException {
    Path target = Files.writeString(tempDir.resolve("notes.txt"), "before");
    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r-----"));

    FileUtils.replaceAtomically(target, new ByteArrayInputStream("after".getBytes()));

    assertEquals(
        PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(target));
  }

  private long directoryEntries() throws IOException {
    try (Stream<Path> entries = Files.list(tempDir)) {
      return entries.count();
    }
  }
}
