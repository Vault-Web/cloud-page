package cloudpage.util;

import static org.assertj.core.api.Assertions.assertThatCode;

import cloudpage.exceptions.InvalidPathException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileUtilTest {

  @TempDir Path tempDir;

  @Test
  void test_resolveExistingFilePath_inRootDirectory_ok() throws IOException {
    Path pathToResolve = tempDir.resolve("testpath");
    Files.createFile(pathToResolve);

    assertThatCode(
            () -> FileUtil.validateAndResolvePathWithinRoot(tempDir.toString(), pathToResolve))
        .as("A path inside the root path should be validated successfully")
        .doesNotThrowAnyException();

    assertThatCode(() -> FileUtil.validateAndResolvePathWithinRoot(tempDir, pathToResolve))
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
            () -> FileUtil.validateAndResolvePathWithinRoot(rootDir.toString(), pathToResolve))
        .as("A path outside the root path should not be validated successfully")
        .isInstanceOf(InvalidPathException.class)
        .hasMessage("Path traversal attempt detected: " + pathToResolve);

    assertThatCode(() -> FileUtil.validateAndResolvePathWithinRoot(rootDir, pathToResolve))
        .as("A path outside the root path should not be validated successfully")
        .isInstanceOf(InvalidPathException.class)
        .hasMessage("Path traversal attempt detected: " + pathToResolve);
  }

  //    @Test
  //    void test_resolveFilePath_useDotNotationForCurrentDir_fail() throws IOException {
  //        Path rootDir = tempDir.resolve("testpath");
  //        Files.createDirectory(rootDir);
  //
  //        Path pathToResolve = Paths.get(rootDir.toString(),".");
  //
  //        assertThatCode(
  //                () -> FileUtil.validateAndResolvePathWithinRoot(rootDir.toString(),
  // pathToResolve))
  //                .as("A path which is equal to the root path should not be validated
  // successfully")
  //                .isInstanceOf(InvalidPathException.class)
  //                .hasMessage("Path traversal attempt detected: " + pathToResolve);
  //
  //        assertThatCode(() -> FileUtil.validateAndResolvePathWithinRoot(rootDir, pathToResolve))
  //                .as("A path which is equal to the root path should not be validated
  // successfully")
  //                .isInstanceOf(InvalidPathException.class)
  //                .hasMessage("Path traversal attempt detected: " + pathToResolve);
  //    }

  @Test
  void test_resolveFilePath_usePathTraversalAttempt_fail() throws IOException {
    Path rootDir = tempDir.resolve("testpath");
    Files.createDirectory(rootDir);

    Path pathToResolve = Paths.get(rootDir.toString(), "../");

    assertThatCode(
            () -> FileUtil.validateAndResolvePathWithinRoot(rootDir.toString(), pathToResolve))
        .as("A path that uses path traversal should not be validated successfully")
        .isInstanceOf(InvalidPathException.class)
        .hasMessage("Path traversal attempt detected: " + pathToResolve);

    assertThatCode(() -> FileUtil.validateAndResolvePathWithinRoot(rootDir, pathToResolve))
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
            () -> FileUtil.validateAndResolvePathWithinRoot(rootDir.toString(), pathToResolve))
        .as("A path that uses path traversal should not be validated successfully")
        .isInstanceOf(InvalidPathException.class)
        .hasMessage("Path traversal attempt detected: " + pathToResolve);

    assertThatCode(() -> FileUtil.validateAndResolvePathWithinRoot(rootDir, pathToResolve))
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
            () -> FileUtil.validateAndResolvePathWithinRoot(rootDir.toString(), pathToResolve))
        .as("A path that uses path traversal should not be validated successfully")
        .isInstanceOf(InvalidPathException.class)
        .hasMessage("Path traversal attempt detected: " + pathToResolve);

    assertThatCode(() -> FileUtil.validateAndResolvePathWithinRoot(rootDir, pathToResolve))
        .as("A path that uses path traversal should not be validated successfully")
        .isInstanceOf(InvalidPathException.class)
        .hasMessage("Path traversal attempt detected: " + pathToResolve);
  }
}
