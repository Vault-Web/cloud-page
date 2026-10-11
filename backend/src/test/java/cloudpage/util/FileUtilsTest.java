package cloudpage.util;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cloudpage.exceptions.InvalidPathException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileUtilsTest {

  @TempDir Path tempDir;

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
}
