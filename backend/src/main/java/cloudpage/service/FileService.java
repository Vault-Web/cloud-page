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

@Service
public class FileService {

  public void uploadFile(String rootPath, String relativeFolderPath, MultipartFile file)
      throws IOException {
    Path folder = Paths.get(rootPath, relativeFolderPath).normalize();
    validatePath(rootPath, folder);

    if (!Files.exists(folder)) {
      Files.createDirectories(folder);
    }

    Path target = folder.resolve(file.getOriginalFilename());
    Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
  }

  public void deleteFile(String rootPath, String relativeFilePath) throws IOException {
    Path file = Paths.get(rootPath, relativeFilePath).normalize();
    validatePath(rootPath, file);
    Files.deleteIfExists(file);
  }

  public void renameOrMoveFile(String rootPath, String relativeFilePath, String relativeNewPath)
      throws IOException {
    Path source = Paths.get(rootPath, relativeFilePath).normalize();
    Path target = Paths.get(rootPath, relativeNewPath).normalize();
    validatePath(rootPath, source);
    validatePath(rootPath, target.getParent());
    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
  }

  public String readFileContent(String rootPath, String relativeFilePath) throws IOException {
    Path file = Paths.get(rootPath, relativeFilePath).normalize();
    validatePath(rootPath, file);

    if (!Files.exists(file) || !Files.isRegularFile(file)) {
      throw new ResourceNotFoundException("File", "FilePath", file.toString());
    }

    return Files.readString(file);
  }

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
}
