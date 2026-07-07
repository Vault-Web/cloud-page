package cloudpage.service;

import cloudpage.dto.FileResource;
import cloudpage.exceptions.FileNotFoundException;
import cloudpage.exceptions.InvalidPathException;
import cloudpage.exceptions.ResourceNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

/**
 * Service for file-level operations within a user's storage area: uploading, deleting, renaming or
 * moving, reading content, and loading files as downloadable resources. All paths are confined to
 * the user's root directory, which acts as a security boundary against path traversal.
 */
@Service
public class FileService {

  /**
   * Uploads a file into the given folder, creating the folder if it does not yet exist. When a
   * quota is supplied, the upload is rejected if it would push the user's total storage usage
   * beyond the limit. An existing file with the same name is overwritten.
   *
   * @param rootPath the root directory of the user, used as a security boundary
   * @param relativeFolderPath the relative path of the destination folder
   * @param file the uploaded file
   * @param quotaMb the storage quota in megabytes, or {@code null} for no quota
   * @throws IOException if the folder cannot be created or the file cannot be written
   * @throws InvalidPathException if the file name is missing or the destination resolves outside
   *     the user's root directory
   * @throws IllegalArgumentException if the upload would exceed the storage quota
   */
  public void uploadFile(
      String rootPath, String relativeFolderPath, MultipartFile file, Long quotaMb)
      throws IOException {
    Path folder = Paths.get(rootPath, relativeFolderPath).normalize();
    validatePath(rootPath, folder);

    if (!Files.exists(folder)) {
      Files.createDirectories(folder);
    }
    long newFileSize = file.getSize();
    long currentSize = calculateDirectorySize(Paths.get(rootPath));

    if (quotaMb != null) {
      long quotaBytes = quotaMb * 1024 * 1024;

      if (currentSize + newFileSize > quotaBytes) {
        throw new IllegalArgumentException(
            "Upload rejected: storage limit of "
                + quotaMb
                + " MB reached. Please delete files to free space.");
      }
    }

    String originalFilename = file.getOriginalFilename();
    if (originalFilename == null || originalFilename.isBlank()) {
      throw new InvalidPathException("Uploaded file must have a name");
    }

    // Strip any directory components from the user-supplied name so a crafted filename
    // (e.g. "../../x" or an absolute path) cannot write outside the user's root directory.
    Path fileName = Paths.get(originalFilename).getFileName();
    if (fileName == null) {
      throw new InvalidPathException("Invalid file name: " + originalFilename);
    }

    Path target = folder.resolve(fileName).normalize();
    validatePath(rootPath, target);
    Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Deletes a file if it exists. Does nothing if the file is already absent.
   *
   * @param rootPath the root directory of the user, used as a security boundary
   * @param relativeFilePath the relative path of the file to delete
   * @throws IOException if the file cannot be deleted
   * @throws InvalidPathException if the file is outside the user's root directory
   */
  public void deleteFile(String rootPath, String relativeFilePath) throws IOException {
    Path file = Paths.get(rootPath, relativeFilePath).normalize();
    validatePath(rootPath, file);
    Files.deleteIfExists(file);
  }

  public java.util.Map<String, String> deleteFiles(String rootPath, java.util.List<String> relativeFilePaths) {
    java.util.Map<String, String> results = new java.util.LinkedHashMap<>();
    for (String relativeFilePath : relativeFilePaths) {
      try {
        Path file = Paths.get(rootPath, relativeFilePath).normalize();
        validatePath(rootPath, file);
        boolean deleted = Files.deleteIfExists(file);
        if(deleted) {
          results.put(relativeFilePath, "SUCCESS");
        }
        else {
          results.put(relativeFilePath, "FAILED: Item not found");
        }
      }
      catch (Exception e) {
        results.put(relativeFilePath, "FAILED: " + e.getMessage());
      }
    }
    return results;
  }

  /**
   * Renames or moves a file to a new location, overwriting any existing file at the destination.
   *
   * @param rootPath the root directory of the user, used as a security boundary
   * @param relativeFilePath the relative path of the file to move
   * @param relativeNewPath the relative destination path
   * @throws IOException if the file cannot be moved
   * @throws InvalidPathException if the source or destination is outside the user's root directory
   */
  public void renameOrMoveFile(String rootPath, String relativeFilePath, String relativeNewPath)
      throws IOException {
    Path source = Paths.get(rootPath, relativeFilePath).normalize();
    Path target = Paths.get(rootPath, relativeNewPath).normalize();
    validatePath(rootPath, source);
    validatePath(rootPath, target.getParent());
    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Reads the entire textual content of a file.
   *
   * @param rootPath the root directory of the user, used as a security boundary
   * @param relativeFilePath the relative path of the file to read
   * @return the file content as a string
   * @throws IOException if the file cannot be read
   * @throws InvalidPathException if the file is outside the user's root directory
   * @throws ResourceNotFoundException if the file does not exist or is not a regular file
   */
  public String readFileContent(String rootPath, String relativeFilePath) throws IOException {
    Path file = Paths.get(rootPath, relativeFilePath).normalize();
    validatePath(rootPath, file);

    if (!Files.exists(file) || !Files.isRegularFile(file)) {
      throw new ResourceNotFoundException("File", "FilePath", file.toString());
    }

    return Files.readString(file);
  }

  /**
   * Validates that a path stays within the user's root directory, guarding against path traversal.
   * Existing paths are resolved through symbolic links; for a non-existent path the existing parent
   * directory is resolved instead so the intended location can still be checked.
   *
   * @param rootPath the root directory of the user, used as a security boundary
   * @param path the path to validate
   * @throws IOException if the real path cannot be resolved
   * @throws InvalidPathException if the path resolves outside the user's root directory
   */
  private void validatePath(String rootPath, Path path) throws IOException {
    Path rootReal = Paths.get(rootPath).toRealPath().normalize();
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
  }

  /**
   * Loads a file as a downloadable {@link Resource}, together with an ETag and last-modified
   * timestamp derived from its size and modification time.
   *
   * @param fullPath the path of the file to load
   * @return a {@link FileResource} wrapping the resource, its ETag, and last-modified time
   * @throws IOException if the file attributes cannot be read
   * @throws FileNotFoundException if the file does not exist, is not a regular file, or is not
   *     readable
   */
  public FileResource loadAsResource(Path fullPath) throws IOException {
    if (!Files.exists(fullPath) || !Files.isRegularFile(fullPath) || !Files.isReadable(fullPath)) {
      throw new FileNotFoundException("File not found: " + fullPath);
    }

    Resource resource = new UrlResource(fullPath.toUri());

    BasicFileAttributes attrs = Files.readAttributes(fullPath, BasicFileAttributes.class);
    String etag = "\"" + attrs.size() + "-" + attrs.lastModifiedTime().toMillis() + "\"";

    long lastModified = attrs.lastModifiedTime().toMillis();

    return new FileResource(resource, etag, lastModified);
  }

  /**
   * Calculates the total size of a directory by recursively summing the sizes of all regular files
   * it contains. Files whose size cannot be read are skipped.
   *
   * @param path the directory to measure
   * @return the total size in bytes of all regular files under {@code path}, or {@code 0} if the
   *     path does not exist
   * @throws IOException if the directory tree cannot be traversed
   */
  private long calculateDirectorySize(Path path) throws IOException {
    if (!Files.exists(path)) return 0;

    return Files.walk(path)
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
