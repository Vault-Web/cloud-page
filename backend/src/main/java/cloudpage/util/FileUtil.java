package cloudpage.util;

import cloudpage.exceptions.InvalidPathException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Contains utility functions for handling and processing files */
public final class FileUtil {

  private FileUtil() {
    throw new IllegalStateException("Utility class");
  }

  /**
   * Validates that a path stays within the user's root directory, guarding against path traversal.
   * Existing paths are resolved through symbolic links; for a non-existent path the existing parent
   * directory is resolved instead so the intended location can still be checked.
   *
   * @param rootPath the root directory of the user, used as a security boundary, as String
   * @param path the path to validate
   * @return The resolved path
   * @throws IOException if the real path cannot be resolved
   * @throws InvalidPathException if the path resolves outside the user's root directory
   */
  public static Path validateAndResolvePathWithinRoot(String rootPath, Path path)
      throws IOException {
    Path rootReal = Paths.get(rootPath).toRealPath().normalize();
    return validateAndResolvePathWithinRoot(rootReal, path);
  }

  /**
   * Validates that a path stays within the user's root directory, guarding against path traversal.
   * Existing paths are resolved through symbolic links; for a non-existent path the existing parent
   * directory is resolved instead so the intended location can still be checked.
   *
   * @param rootReal the root directory of the user, used as a security boundary
   * @param path the path to validate
   * @return The resolved path
   * @throws IOException if the real path cannot be resolved
   * @throws InvalidPathException if the path resolves outside the user's root directory
   */
  public static Path validateAndResolvePathWithinRoot(Path rootReal, Path path) throws IOException {
    Path pathReal;

    // If path exists, resolve symlinks to get the real path
    if (Files.exists(path)) {
      pathReal = path.toRealPath().normalize();
    } else {
      // For non-existent paths, resolve the parent if it exists
      Path parent = path.getParent();
      if (parent != null && Files.exists(parent)) {
        Path parentReal = parent.toRealPath().normalize();
        // Check if the resolved parent is within root
        if (!parentReal.startsWith(rootReal)) {
          throw new InvalidPathException("Path traversal attempt detected: " + path);
        }
        // Construct the child path from the resolved parent
        Path fileName = path.getFileName();
        if (fileName != null) {
          pathReal = parentReal.resolve(fileName).normalize();
        } else {
          pathReal = parentReal;
        }
      } else {
        // Parent doesn't exist or is null, validate using absolute path
        // This is a fallback for edge cases
        pathReal = path.toAbsolutePath().normalize();
      }
    }

    if (!pathReal.startsWith(rootReal)) {
      throw new InvalidPathException("Path traversal attempt detected: " + path);
    }

    return pathReal;
  }
}
