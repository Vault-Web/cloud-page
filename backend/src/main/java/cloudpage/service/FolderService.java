package cloudpage.service;

import cloudpage.dto.FileDto;
import cloudpage.dto.FolderContentItemDto;
import cloudpage.dto.FolderDto;
import cloudpage.dto.PageResponseDto;
import cloudpage.exceptions.FileAccessException;
import cloudpage.exceptions.FileDeletionException;
import cloudpage.exceptions.InvalidPathException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class FolderService {

  public FolderDto getFolderTree(String rootPath) throws IOException {
    Path root = Paths.get(rootPath);
    validateRoot(root);
    return readFolder(rootPath, root);
  }

  public FolderDto getFolderTree(String rootPath, String relativePath) throws IOException {
    Path folder = Paths.get(rootPath, relativePath).normalize();
    validatePath(rootPath, folder);
    return readFolder(rootPath, folder);
  }

  public Path createFolder(String rootPath, String relativeParentPath, String name)
      throws IOException {
    Path parent = Paths.get(rootPath, relativeParentPath).normalize();
    validatePath(rootPath, parent);
    Path newFolder = parent.resolve(name);
    return Files.createDirectory(newFolder);
  }

  public void deleteFolder(String rootPath, String relativeFolderPath) throws IOException {
    Path folder = Paths.get(rootPath, relativeFolderPath).normalize();
    validatePath(rootPath, folder);

    Files.walk(folder)
        .sorted((a, b) -> b.compareTo(a))
        .forEach(
            p -> {
              try {
                Files.delete(p);
              } catch (IOException e) {
                throw new FileDeletionException(
                    "Failed to delete: " + p + " with exception: " + e.getMessage());
              }
            });
  }

  public void renameOrMoveFolder(String rootPath, String relativeFolderPath, String relativeNewPath)
      throws IOException {
    Path source = Paths.get(rootPath, relativeFolderPath).normalize();
    Path target = Paths.get(rootPath, relativeNewPath).normalize();
    validatePath(rootPath, source);
    validatePath(rootPath, target.getParent());
    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
  }

  public PageResponseDto<FolderContentItemDto> getFolderContentPage(
      String rootPath, String relativePath, int page, int size, String sort) throws IOException {
    if (page < 0) {
      throw new IllegalArgumentException("page must be greater than or equal to 0");
    }
    if (size <= 0) {
      throw new IllegalArgumentException("size must be greater than 0");
    }

    Path folder =
        (relativePath == null || relativePath.isBlank())
            ? Paths.get(rootPath)
            : Paths.get(rootPath, relativePath).normalize();

    validatePath(rootPath, folder);
    if (!Files.exists(folder) || !Files.isDirectory(folder)) {
      throw new InvalidPathException("Folder does not exist or is not a directory: " + folder);
    }

    // Resolve root path once for relative path calculation
    Path rootReal = Paths.get(rootPath).toRealPath().normalize();

    List<FolderContentItemDto> items = new ArrayList<>();

    try (var stream = Files.list(folder)) {
      items =
          stream
              .map(
                  path -> {
                    // Validate each child path to prevent symlink escapes
                    try {
                      validatePath(rootPath, path);
                    } catch (IOException e) {
                      throw new InvalidPathException(
                          "Invalid path detected while listing: " + path + " - " + e.getMessage());
                    }

                    // Calculate relative path from root to child
                    String itemRelativePath;
                    try {
                      Path pathReal = path.toRealPath().normalize();
                      itemRelativePath = rootReal.relativize(pathReal).toString();
                      // Use forward slashes for consistency across platforms
                      itemRelativePath = itemRelativePath.replace('\\', '/');
                    } catch (IOException e) {
                      // Fallback to simple relativize if toRealPath fails
                      itemRelativePath =
                          Paths.get(rootPath).relativize(path.toAbsolutePath()).toString();
                      itemRelativePath = itemRelativePath.replace('\\', '/');
                    }

                    boolean isDirectory = Files.isDirectory(path);
                    long sizeValue = 0L;
                    String mimeType = null;
                    long lastModifiedAt;

                    try {
                      BasicFileAttributes attrs =
                          Files.readAttributes(path, BasicFileAttributes.class);

                      lastModifiedAt = attrs.lastModifiedTime().toMillis();

                      if (!isDirectory) {
                        sizeValue = attrs.size();
                        mimeType = Files.probeContentType(path);
                      }
                    } catch (IOException e) {
                      throw new FileAccessException(
                          "Failed to read file attributes: "
                              + path
                              + " with exception: "
                              + e.getMessage());
                    }

                    return new FolderContentItemDto(
                        path.getFileName().toString(),
                        itemRelativePath,
                        isDirectory,
                        sizeValue,
                        mimeType,
                        lastModifiedAt);
                  })
              .collect(Collectors.toList());
    }

    applySorting(items, sort);

    long totalElements = items.size();
    int totalPages = (int) Math.ceil(totalElements / (double) size);

    int fromIndex = page * size;
    int toIndex = Math.min(fromIndex + size, items.size());

    List<FolderContentItemDto> pageContent =
        fromIndex >= items.size() ? List.of() : items.subList(fromIndex, toIndex);

    return new PageResponseDto<>(pageContent, totalElements, totalPages, page);
  }

  private void applySorting(List<FolderContentItemDto> items, String sort) {
    String sortField = "name";
    boolean ascending = true;

    if (sort != null && !sort.isBlank()) {
      String[] parts = sort.split(",");
      if (parts.length > 0 && !parts[0].isBlank()) {
        sortField = parts[0];
      }
      if (parts.length > 1 && !parts[1].isBlank()) {
        ascending = !"desc".equalsIgnoreCase(parts[1]);
      }
    }

    Comparator<FolderContentItemDto> comparator;
    switch (sortField) {
      case "name":
      default:
        comparator =
            Comparator.comparing(FolderContentItemDto::getName, String.CASE_INSENSITIVE_ORDER);
        break;
    }

    if (!ascending) {
      comparator = comparator.reversed();
    }

    items.sort(comparator);
  }

  private FolderDto readFolder(String rootPath, Path path) throws IOException {
    // Resolve root path once for relative path calculation
    Path rootReal = Paths.get(rootPath).toRealPath().normalize();

    List<FolderDto> subfolders =
        Files.list(path)
            .filter(Files::isDirectory)
            .map(
                subPath -> {
                  try {
                    // Validate each child path to prevent symlink escapes
                    validatePath(rootPath, subPath);
                    return readFolder(rootPath, subPath);
                  } catch (IOException e) {
                    throw new InvalidPathException(
                        "Invalid path detected while reading folder: "
                            + subPath
                            + " - "
                            + e.getMessage());
                  }
                })
            .collect(Collectors.toList());

    List<FileDto> files =
        Files.list(path)
            .filter(Files::isRegularFile)
            .map(
                filePath -> {
                  try {
                    // Validate each child path to prevent symlink escapes
                    validatePath(rootPath, filePath);

                    // Calculate relative path from root to file
                    String relativePath;
                    try {
                      Path filePathReal = filePath.toRealPath().normalize();
                      relativePath = rootReal.relativize(filePathReal).toString();
                      // Use forward slashes for consistency across platforms
                      relativePath = relativePath.replace('\\', '/');
                    } catch (IOException e) {
                      // Fallback to simple relativize if toRealPath fails
                      relativePath =
                          Paths.get(rootPath).relativize(filePath.toAbsolutePath()).toString();
                      relativePath = relativePath.replace('\\', '/');
                    }

                    BasicFileAttributes attrs =
                        Files.readAttributes(filePath, BasicFileAttributes.class);
                    return new FileDto(
                        filePath.getFileName().toString(),
                        relativePath,
                        attrs.size(),
                        Files.probeContentType(filePath),
                        attrs.lastModifiedTime().toMillis());
                  } catch (IOException e) {
                    throw new FileAccessException(
                        "Failed to read file attributes: "
                            + filePath
                            + " with exception: "
                            + e.getMessage());
                  }
                })
            .collect(Collectors.toList());

    // Calculate relative path for the current folder
    String folderRelativePath;
    try {
      Path pathReal = path.toRealPath().normalize();
      folderRelativePath = rootReal.relativize(pathReal).toString();
      // Use forward slashes for consistency across platforms
      folderRelativePath = folderRelativePath.replace('\\', '/');
      // Empty string means root folder
      if (folderRelativePath.isEmpty()) {
        folderRelativePath = ".";
      }
    } catch (IOException e) {
      // Fallback to simple relativize if toRealPath fails
      folderRelativePath = Paths.get(rootPath).relativize(path.toAbsolutePath()).toString();
      folderRelativePath = folderRelativePath.replace('\\', '/');
      if (folderRelativePath.isEmpty()) {
        folderRelativePath = ".";
      }
    }

    try {
      BasicFileAttributes folderAttrs = Files.readAttributes(path, BasicFileAttributes.class);
      return new FolderDto(
          path.getFileName().toString(),
          folderRelativePath,
          subfolders,
          files,
          folderAttrs.lastModifiedTime().toMillis());
    } catch (IOException e) {
      throw new FileAccessException(
          "Failed to read folder attributes: " + path + " with exception: " + e.getMessage());
    }
  }

  public void validatePath(String rootPath, Path path) throws IOException {
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

  private void validateRoot(Path root) {
    if (!Files.exists(root) || !Files.isDirectory(root)) {
      throw new InvalidPathException("Root folder does not exist or is not a directory: " + root);
    }
  }
}
