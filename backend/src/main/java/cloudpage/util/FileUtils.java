package cloudpage.util;

import cloudpage.exceptions.InvalidPathException;
import cloudpage.service.TrashService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/** Contains utility functions for handling and processing files */
public final class FileUtils {

  private FileUtils() {
    throw new IllegalStateException("Utility class");
  }

  public static void validatePath(String rootPath, Path path) throws IOException {
    FileUtils.validateAndResolvePathWithinRoot(rootPath, path);
  }

  public static void validatePath(Path rootPath, Path path) throws IOException {
    FileUtils.validateAndResolvePathWithinRoot(rootPath, path);
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
    return validateAndResolvePathWithinRoot(Paths.get(rootPath).toRealPath().normalize(), path);
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

  public static void move(
      String rootPath, String relativeSourcePath, String relativeDestinationPath)
      throws IOException {
    Path source = Paths.get(rootPath, relativeSourcePath).normalize();
    Path target = Paths.get(rootPath, relativeDestinationPath).normalize();
    validatePath(rootPath, source);
    validatePath(rootPath, target);
    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Ensures that adding bytes to active storage would not exceed the owner's storage quota.
   *
   * @param rootPath the root directory of the user
   * @param additionalBytes the number of bytes to add to active storage
   * @param quotaMb the storage quota in megabytes, or {@code null} for no quota
   * @throws IOException if the directory tree cannot be traversed
   * @throws IllegalArgumentException if the additional storage would exceed the quota
   */
  public static void validateStorageSizeWithinQuota(
      String rootPath, long additionalBytes, Long quotaMb) throws IOException {
    if (quotaMb == null) {
      return;
    }

    long currentSize = calculateDirectorySize(Paths.get(rootPath));
    long quotaBytes = Math.multiplyExact(quotaMb, 1024L * 1024L);
    long projectedSize = Math.addExact(currentSize, additionalBytes);
    if (projectedSize > quotaBytes) {
      throw new IllegalArgumentException("Storage limit of " + quotaMb + " MB would be exceeded");
    }
  }

  /**
   * Calculates active storage usage by recursively summing regular files outside the root's {@code
   * .trash} directory. Files whose size cannot be read are skipped.
   *
   * @param path the directory to measure
   * @return the total size in bytes of all regular files under {@code path}, or {@code 0} if the
   *     path does not exist
   * @throws IOException if the directory tree cannot be traversed
   */
  public static long calculateDirectorySize(Path path) throws IOException {
    if (!Files.exists(path)) return 0;

    Path trashPath = path.resolve(TrashService.TRASH_DIR);
    try (Stream<Path> paths = Files.walk(path)) {
      return paths
          .filter(p -> !p.startsWith(trashPath))
          .filter(Files::isRegularFile)
          .mapToLong(
              p -> {
                try {
                  return Files.size(p);
                } catch (IOException e) {
                  return 0;
                }
              })
          .sum();
    }
  }

  public static void rejectTrashPath(Path path) {
    for (Path part : path) {
      if (TrashService.TRASH_DIR.equals(part.toString())) {
        throw new InvalidPathException("Trash folders cannot be accessed directly");
      }
    }
  }
}
