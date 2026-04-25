package cloudpage.service;

import cloudpage.dto.*;
import cloudpage.exceptions.FileAccessException;
import cloudpage.exceptions.FileDeletionException;
import cloudpage.exceptions.FileNotFoundException;
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
import java.util.Locale;
import java.util.stream.Collectors;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Service;

@Service
public class FolderService {

  private final JaroWinklerSimilarity jaroWinkler = new JaroWinklerSimilarity();

  /**
   * Searches for files and folders within a given folder using the Jaro-Winkler similarity
   * algorithm. Results are sorted by score in descending order.
   *
   * @param rootPath the root directory of the user, used as a security boundary
   * @param folderPath the relative path of the folder to search in
   * @param query the search term to match against file and folder names
   * @param maxResults the maximum number of results to return
   * @param minScore the minimum similarity score (0-100) a result must have to be included
   * @return a list of matching files and folders sorted by score descending
   * @throws InvalidPathException if the folder path is outside the user's root directory
   * @throws FileNotFoundException if the folder does not exist
   * @throws IOException if an error occurs while walking the file tree
   */
  public List<SearchResult> searchInFolder(
      String rootPath, String folderPath, String query, int maxResults, int minScore)
      throws IOException {

    Path folder = Paths.get(rootPath, folderPath).normalize();
    validatePath(rootPath, folder);

    if (!Files.exists(folder) || !Files.isDirectory(folder)) {
      throw new FileNotFoundException("Folder not found: " + folderPath);
    }

    // Locale.ROOT prevents using the system's local language for case conversion
    String lowerQuery = query.toLowerCase(Locale.ROOT);

    try (var stream = Files.walk(folder)) {
      return stream
          .filter(p -> !p.equals(folder))
          .map(p -> createSearchResult(p, lowerQuery))
          .filter(r -> r.getScore() >= minScore)
          .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
          .limit(maxResults)
          .collect(Collectors.toList());
    }
  }

  /**
   * Creates a {@link SearchResult} for a given path by calculating its similarity to the query. The
   * score is calculated using the Jaro-Winkler algorithm. If the file name contains the query as a
   * substring, the score is boosted to at least 95.
   *
   * @param path the path of the file or folder to evaluate
   * @param query the search term in lowercase
   * @return a {@link SearchResult} with the calculated similarity score
   * @throws FileNotFoundException if the file or folder metadata cannot be read
   */
  private SearchResult createSearchResult(Path path, String query) {
    String name = path.getFileName().toString();
    // Locale.ROOT prevents using the system's local language for case conversion
    String lowerName = name.toLowerCase(Locale.ROOT);

    // Jaro-Winkler Score
    int score = (int) (jaroWinkler.apply(lowerName, query) * 100);

    // Boost für Substring
    if (lowerName.contains(query)) {
      score = Math.max(score, 95);
    }

    try {
      boolean isDir = Files.isDirectory(path);
      return new SearchResult(
          name,
          path.toString(),
          isDir ? "folder" : "file",
          isDir ? null : Files.size(path),
          isDir ? null : Files.probeContentType(path),
          score);
    } catch (IOException e) {
      throw new FileNotFoundException("Could not read file or folder: " + path);
    }
  }

  public FolderDto getFolderTree(String rootPath) throws IOException {
    return getFolderTree(rootPath, false);
  }

  public FolderDto getFolderTree(String rootPath, boolean includeChildCounts) throws IOException {
    Path root = Paths.get(rootPath);
    validateRoot(root);
    return readFolderShallow(rootPath, root, includeChildCounts);
  }

  public FolderDto getFolderTree(String rootPath, String relativePath) throws IOException {
    return getFolderTree(rootPath, relativePath, false);
  }

  public FolderDto getFolderTree(String rootPath, String relativePath, boolean includeChildCounts)
      throws IOException {
    Path folder = Paths.get(rootPath, relativePath).normalize();
    validatePath(rootPath, folder);
    return readFolderShallow(rootPath, folder, includeChildCounts);
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

    try (var stream = Files.walk(folder)) {
      stream
          .sorted((a, b) -> b.compareTo(a))
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (IOException e) {
                  throw new FileDeletionException(
                      "Failed to delete: " + p + "with exception : " + e.getMessage());
                }
              });
    }
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

    // Resolve root path once for relative path calculation and path validation
    Path rootReal = Paths.get(rootPath).toRealPath().normalize();

    List<FolderContentItemDto> items = new ArrayList<>();

    try (var stream = Files.list(folder)) {
      items =
          stream
              .map(
                  path -> {
                    try {
                      Path pathReal = resolvePathWithinRoot(rootReal, path);
                      String itemRelativePath = toRelativePath(rootReal, pathReal);

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
                    } catch (IOException e) {
                      throw new InvalidPathException(
                          "Invalid path detected while listing: " + path + " - " + e.getMessage());
                    }
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

  private FolderDto readFolderShallow(String rootPath, Path path, boolean includeChildCounts)
      throws IOException {
    // Resolve root path once for relative path calculation and path validation
    Path rootReal = Paths.get(rootPath).toRealPath().normalize();
    Path folderPathReal = resolvePathWithinRoot(rootReal, path);

    List<FolderListItemDto> subfolders = new ArrayList<>();
    List<FileDto> files = new ArrayList<>();
    try (var stream = Files.list(path)) {
      stream.forEach(
          childPath -> {
            try {
              // Resolve and validate once per child to avoid repeated toRealPath() calls.
              Path childPathReal = resolvePathWithinRoot(rootReal, childPath);

              if (Files.isDirectory(childPath)) {
                subfolders.add(
                    toFolderListItemDto(rootReal, childPath, childPathReal, includeChildCounts));
              } else if (Files.isRegularFile(childPath)) {
                files.add(toFileDto(rootReal, childPath, childPathReal));
              }
            } catch (IOException e) {
              throw new InvalidPathException(
                  "Invalid path detected while reading folder: "
                      + childPath
                      + " - "
                      + e.getMessage());
            }
          });
    }

    // Calculate relative path for the current folder
    String folderRelativePath = toRelativePath(rootReal, folderPathReal);

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

  private FolderListItemDto toFolderListItemDto(
      Path rootReal, Path path, Path pathReal, boolean includeChildCounts) {
    String folderRelativePath = toRelativePath(rootReal, pathReal);

    try {
      BasicFileAttributes folderAttrs = Files.readAttributes(path, BasicFileAttributes.class);
      return new FolderListItemDto(
          path.getFileName().toString(),
          folderRelativePath,
          includeChildCounts ? countDirectChildren(rootReal, path) : -1L,
          folderAttrs.lastModifiedTime().toMillis());
    } catch (IOException e) {
      throw new FileAccessException(
          "Failed to read folder attributes: " + path + " with exception: " + e.getMessage());
    }
  }

  private FileDto toFileDto(Path rootReal, Path filePath, Path filePathReal) {
    try {
      String relativePath = toRelativePath(rootReal, filePathReal);

      BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
      return new FileDto(
          filePath.getFileName().toString(),
          relativePath,
          attrs.size(),
          Files.probeContentType(filePath),
          attrs.lastModifiedTime().toMillis());
    } catch (IOException e) {
      throw new FileAccessException(
          "Failed to read file attributes: " + filePath + " with exception: " + e.getMessage());
    }
  }

  private long countDirectChildren(Path rootReal, Path directoryPath) {
    try (var stream = Files.list(directoryPath)) {
      return stream
          .filter(
              childPath -> {
                try {
                  validatePath(rootReal, childPath);
                } catch (IOException e) {
                  throw new InvalidPathException(
                      "Invalid path detected while counting: "
                          + childPath
                          + " - "
                          + e.getMessage());
                }
                return Files.isDirectory(childPath) || Files.isRegularFile(childPath);
              })
          .count();
    } catch (IOException e) {
      throw new FileAccessException(
          "Failed to count direct children for: "
              + directoryPath
              + " with exception: "
              + e.getMessage());
    }
  }

  public void validatePath(String rootPath, Path path) throws IOException {
    Path rootReal = Paths.get(rootPath).toRealPath().normalize();
    resolvePathWithinRoot(rootReal, path);
  }

  private void validatePath(Path rootReal, Path path) throws IOException {
    resolvePathWithinRoot(rootReal, path);
  }

  private Path resolvePathWithinRoot(Path rootReal, Path path) throws IOException {
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

  private String toRelativePath(Path rootReal, Path pathReal) {
    String relativePath = rootReal.relativize(pathReal).toString().replace('\\', '/');
    return relativePath.isEmpty() ? "." : relativePath;
  }

  private void validateRoot(Path root) {
    if (!Files.exists(root) || !Files.isDirectory(root)) {
      throw new InvalidPathException("Root folder does not exist or is not a directory: " + root);
    }
  }
}
