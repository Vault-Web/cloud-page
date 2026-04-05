package cloudpage.service;

import cloudpage.dto.*;
import cloudpage.exceptions.FileDeletionException;
import cloudpage.exceptions.FileNotFoundException;
import cloudpage.exceptions.InvalidPathException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
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

    String lowerQuery = query.toLowerCase();

    return Files.walk(folder)
        .filter(p -> !p.equals(folder))
        .map(p -> createSearchResult(p, lowerQuery))
        .filter(r -> r.getScore() >= minScore)
        .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
        .limit(maxResults)
        .collect(Collectors.toList());
  }

  /**
   * Creates a {@link SearchResult} for a given path by calculating its similarity to the query.
   * The score is calculated using the Jaro-Winkler algorithm. If the file name contains the query
   * as a substring, the score is boosted to at least 95.
   *
   * @param path the path of the file or folder to evaluate
   * @param query the search term in lowercase
   * @return a {@link SearchResult} with the calculated similarity score
   * @throws FileNotFoundException if the file or folder metadata cannot be read
   */
  private SearchResult createSearchResult(Path path, String query) {
    String name = path.getFileName().toString();
    String lowerName = name.toLowerCase();

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
    Path root = Paths.get(rootPath);
    validateRoot(root);
    return readFolder(root);
  }

  public FolderDto getFolderTree(String rootPath, String relativePath) throws IOException {
    Path folder = Paths.get(rootPath, relativePath).normalize();
    validatePath(rootPath, folder);
    return readFolder(folder);
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
                    "Failed to delete: " + p + "with exception : " + e.getMessage());
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

  private FolderDto readFolder(Path path) throws IOException {
    List<FolderDto> subfolders =
        Files.list(path)
            .filter(Files::isDirectory)
            .map(
                subPath -> {
                  try {
                    return readFolder(subPath);
                  } catch (IOException e) {
                    throw new FileDeletionException(
                        "Failed to read: " + path + "with exception : " + e.getMessage());
                  }
                })
            .collect(Collectors.toList());

    List<FileDto> files =
        Files.list(path)
            .filter(Files::isRegularFile)
            .map(
                filePath -> {
                  try {
                    BasicFileAttributes attrs =
                        Files.readAttributes(filePath, BasicFileAttributes.class);
                    return new FileDto(
                        filePath.getFileName().toString(),
                        filePath.toAbsolutePath().toString(),
                        attrs.size(),
                        Files.probeContentType(filePath));
                  } catch (IOException e) {
                    throw new FileDeletionException(
                        "Failed to read: " + filePath + "with exception : " + e.getMessage());
                  }
                })
            .collect(Collectors.toList());

    return new FolderDto(
        path.getFileName().toString(), path.toAbsolutePath().toString(), subfolders, files);
  }

  public void validatePath(String rootPath, Path path) {
    if (!path.toAbsolutePath().startsWith(Paths.get(rootPath).toAbsolutePath())) {
      throw new InvalidPathException("Access outside the user's root folder is forbidden: " + path);
    }
  }

  private void validateRoot(Path root) {
    if (!Files.exists(root) || !Files.isDirectory(root)) {
      throw new InvalidPathException("Root folder does not exist or is not a directory: " + root);
    }
  }
}
