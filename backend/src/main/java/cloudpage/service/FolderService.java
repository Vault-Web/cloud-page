package cloudpage.service;

import cloudpage.dto.*;
import cloudpage.exceptions.FileAccessException;
import cloudpage.exceptions.FileDeletionException;
import cloudpage.exceptions.FileNotFoundException;
import cloudpage.exceptions.InvalidPathException;
import cloudpage.util.ChecksumUtils;
import cloudpage.util.FileUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Service;

@Service
public class FolderService {

  private static final long DEFERRED_SIZE = -1L;

  private final Map<Path, CachedChecksum> checksumCache = new ConcurrentHashMap<>();
  private final Map<Path, DuplicateIndex> duplicateIndexCache = new ConcurrentHashMap<>();

  private static final long DUPLICATE_INDEX_TTL_MS = 30_000L;

  private record CachedChecksum(long size, long lastModified, String checksum) {}

  private record DuplicateIndex(long createdAt, Map<Long, List<Path>> filesBySize) {}

  private String getCachedChecksum(Path path) throws IOException {
    BasicFileAttributes attrs =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

    long size = attrs.size();
    long lastModified = attrs.lastModifiedTime().toMillis();

    CachedChecksum cached = checksumCache.get(path);

    if (cached != null && cached.size() == size && cached.lastModified() == lastModified) {
      return cached.checksum();
    }

    String checksum;

    try (InputStream inputStream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      checksum = ChecksumUtils.calculateChecksum(inputStream, CHECKSUM_ALGORITHM);
    }

    checksumCache.put(path, new CachedChecksum(size, lastModified, checksum));

    return checksum;
  }

  private DuplicateIndex getDuplicateIndex(Path rootReal) throws IOException {
    long now = System.currentTimeMillis();

    DuplicateIndex cached = duplicateIndexCache.get(rootReal);

    if (cached != null && now - cached.createdAt() < DUPLICATE_INDEX_TTL_MS) {
      return cached;
    }

    Map<Long, List<Path>> filesBySize = new HashMap<>();
    Path trashDir = rootReal.resolve(FileUtils.TRASH_DIR);

    Files.walkFileTree(
        rootReal,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile() && attrs.size() > 0 && !file.startsWith(trashDir)) {

              filesBySize.computeIfAbsent(attrs.size(), key -> new ArrayList<>()).add(file);
            }

            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
          }
        });

    DuplicateIndex index = new DuplicateIndex(now, filesBySize);
    duplicateIndexCache.put(rootReal, index);

    return index;
  }

  private static final String CHECKSUM_ALGORITHM = "SHA-256";

  private static final Pattern SYNCTHING_CONFLICT_PATTERN =
      Pattern.compile("^(.+)\\.sync-conflict-\\d{8}-\\d{6}-[^.]+(\\..+)?$");

  private final JaroWinklerSimilarity jaroWinkler = new JaroWinklerSimilarity();

  private boolean isSyncthingConflict(String fileName) {
    return SYNCTHING_CONFLICT_PATTERN.matcher(fileName).matches();
  }

  private void markDuplicatesForPage(Path rootReal, List<FolderContentItemDto> pageContent)
      throws IOException {

    Set<Long> relevantSizes = new HashSet<>();

    for (FolderContentItemDto item : pageContent) {
      if (!item.isDirectory() && item.getSize() > 0) {
        relevantSizes.add(item.getSize());
      }
    }

    if (relevantSizes.isEmpty()) {
      return;
    }

    DuplicateIndex duplicateIndex = getDuplicateIndex(rootReal);
    Map<Long, List<Path>> candidatesBySize = duplicateIndex.filesBySize();

    for (FolderContentItemDto item : pageContent) {
      if (item.isDirectory() || item.getSize() == 0) {
        continue;
      }

      Path currentPath = rootReal.resolve(item.getPath()).normalize();

      try {
        String currentChecksum = getCachedChecksum(currentPath);

        for (Path candidate : candidatesBySize.getOrDefault(item.getSize(), List.of())) {
          if (candidate.normalize().equals(currentPath)) {
            continue;
          }

          if (getCachedChecksum(candidate).equals(currentChecksum)) {
            item.setDuplicate(true);
            break;
          }
        }
      } catch (IOException e) {
        // Leave duplicate=false when the file cannot be read.
      }
    }
  }

  private String inferOriginalFileName(String fileName) {
    var matcher = SYNCTHING_CONFLICT_PATTERN.matcher(fileName);

    if (!matcher.matches()) {
      return null;
    }

    String baseName = matcher.group(1);
    String extension = matcher.group(2);

    return baseName + (extension != null ? extension : "");
  }

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
    return searchInFolder(rootPath, folderPath, query, maxResults, minScore, null);
  }

  public List<SearchResult> searchInFolder(
      String rootPath,
      String folderPath,
      String query,
      int maxResults,
      int minScore,
      SearchFilter filter)
      throws IOException {

    Path rootAbsolute = Paths.get(rootPath).toAbsolutePath().normalize();
    Path folder = Paths.get(rootPath, folderPath).normalize();
    FileUtils.validatePath(rootPath, folder);

    if (!Files.exists(folder) || !Files.isDirectory(folder)) {
      throw new FileNotFoundException("Folder not found: " + folderPath);
    }

    // Locale.ROOT prevents using the system's local language for case conversion
    String lowerQuery = query.toLowerCase(Locale.ROOT);
    SearchFilter effectiveFilter = filter != null ? filter : new SearchFilter();

    Path trashDir = Paths.get(rootPath, FileUtils.TRASH_DIR).normalize();
    try (var stream = Files.walk(folder)) {
      return stream
          .filter(p -> !p.equals(folder))
          .filter(p -> !p.startsWith(trashDir))
          .map(p -> createSearchResult(p, lowerQuery, rootAbsolute))
          .filter(r -> r.getScore() >= minScore)
          .filter(r -> matchesFilter(r, effectiveFilter))
          .sorted(searchComparator(effectiveFilter))
          .limit(maxResults)
          .collect(Collectors.toList());
    }
  }

  /** Returns whether a search result satisfies every set metadata filter. */
  private boolean matchesFilter(SearchResult result, SearchFilter filter) {
    if (filter.getType() != null
        && !filter.getType().isBlank()
        && !filter.getType().equalsIgnoreCase(result.getType())) {
      return false;
    }
    if (filter.getMimeType() != null && !filter.getMimeType().isBlank()) {
      String mimeType = result.getMimeType();
      if (mimeType == null
          || !mimeType
              .toLowerCase(Locale.ROOT)
              .startsWith(filter.getMimeType().toLowerCase(Locale.ROOT))) {
        return false;
      }
    }
    Long size = result.getSize();
    if (filter.getMinSize() != null && (size == null || size < filter.getMinSize())) {
      return false;
    }
    if (filter.getMaxSize() != null && (size == null || size > filter.getMaxSize())) {
      return false;
    }
    Long modified = result.getLastModifiedAt();
    if (filter.getModifiedAfter() != null
        && (modified == null || modified <= filter.getModifiedAfter())) {
      return false;
    }
    return filter.getModifiedBefore() == null
        || (modified != null && modified < filter.getModifiedBefore());
  }

  /** Builds the result comparator for the requested sort field and direction. */
  private Comparator<SearchResult> searchComparator(SearchFilter filter) {
    String sortBy =
        filter.getSortBy() == null ? "relevance" : filter.getSortBy().toLowerCase(Locale.ROOT);
    Comparator<SearchResult> comparator;
    switch (sortBy) {
      case "name":
        comparator = Comparator.comparing(SearchResult::getName, String.CASE_INSENSITIVE_ORDER);
        break;
      case "size":
        comparator =
            Comparator.comparing((SearchResult r) -> r.getSize() == null ? -1L : r.getSize());
        break;
      case "lastmodified":
        comparator =
            Comparator.comparing(
                (SearchResult r) -> r.getLastModifiedAt() == null ? -1L : r.getLastModifiedAt());
        break;
      default:
        comparator = Comparator.comparingInt(SearchResult::getScore);
    }
    return filter.isAscending() ? comparator : comparator.reversed();
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
  private SearchResult createSearchResult(Path path, String query, Path rootAbsolute) {
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
      Path lexicalPath = path.toAbsolutePath().normalize();
      String relativePath = toRelativePath(rootAbsolute, lexicalPath);
      boolean isDir = Files.isDirectory(path);
      return new SearchResult(
          name,
          relativePath,
          isDir ? "folder" : "file",
          isDir ? null : Files.size(path),
          isDir ? null : Files.probeContentType(path),
          Files.getLastModifiedTime(path).toMillis(),
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
    FileUtils.validatePath(rootPath, folder);
    return readFolderShallow(rootPath, folder, includeChildCounts);
  }

  public Path createFolder(String rootPath, String relativeParentPath, String name)
      throws IOException {
    FileUtils.rejectTrashPath(Paths.get(relativeParentPath).resolve(name).normalize());
    Path parent = Paths.get(rootPath, relativeParentPath).normalize();
    FileUtils.validatePath(rootPath, parent);
    Path newFolder = parent.resolve(name).normalize();
    FileUtils.validatePath(rootPath, newFolder);
    return Files.createDirectory(newFolder);
  }

  public void deleteFolder(String rootPathString, String relativeFolderPath) throws IOException {
    FileUtils.rejectTrashPath(Paths.get(relativeFolderPath).normalize());
    Path rootPath = Paths.get(rootPathString).normalize();
    Path folder = rootPath.resolve(relativeFolderPath).normalize();
    FileUtils.validatePath(rootPathString, folder);
    if (folder.equals(rootPath)) {
      throw new FileDeletionException("Cannot delete root folder.");
    }

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

  public void moveFolder(String rootPath, String relativeSourcePath, String relativeDestinationPath)
      throws IOException {
    FileUtils.rejectTrashPath(Paths.get(relativeSourcePath).normalize());
    FileUtils.rejectTrashPath(Paths.get(relativeDestinationPath).normalize());
    FileUtils.move(rootPath, relativeSourcePath, relativeDestinationPath);
  }

  public PageResponseDto<FolderContentItemDto> getFolderContentPage(
      String rootPath, String relativePath, int page, int size, String sort) throws IOException {
    return getFolderContentPage(rootPath, relativePath, page, size, sort, true);
  }

  public PageResponseDto<FolderContentItemDto> getFolderContentPage(
      String rootPath,
      String relativePath,
      int page,
      int size,
      String sort,
      boolean includeDirectorySizes)
      throws IOException {
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

    FileUtils.validatePath(rootPath, folder);
    if (!Files.exists(folder) || !Files.isDirectory(folder)) {
      throw new InvalidPathException("Folder does not exist or is not a directory: " + folder);
    }

    // Resolve root path once for relative path calculation and path validation
    Path rootReal = Paths.get(rootPath).toRealPath().normalize();

    boolean eagerSizes = sortsBySize(sort);
    List<FolderContentItemDto> items = new ArrayList<>();

    try (var stream = Files.list(folder)) {
      Path trashDir = Paths.get(rootPath, FileUtils.TRASH_DIR).normalize();
      items =
          stream
              .filter(path -> !path.normalize().equals(trashDir))
              .map(
                  path -> {
                    try {
                      if (Files.isSymbolicLink(path)) {
                        return null;
                      }
                      Path pathReal = FileUtils.validateAndResolvePathWithinRoot(rootReal, path);
                      String itemRelativePath = toRelativePath(rootReal, pathReal);

                      boolean isDirectory = Files.isDirectory(path);
                      boolean duplicate = false;
                      String fileName = path.getFileName().toString();
                      boolean conflict = !isDirectory && isSyncthingConflict(fileName);

                      String originalPath = null;

                      if (conflict) {
                        String originalFileName = inferOriginalFileName(fileName);
                        Path originalFile = path.resolveSibling(originalFileName);

                        if (Files.isRegularFile(originalFile, LinkOption.NOFOLLOW_LINKS)) {
                          Path originalFileReal =
                              FileUtils.validateAndResolvePathWithinRoot(rootReal, originalFile);
                          originalPath = toRelativePath(rootReal, originalFileReal);
                        }
                      }
                      long sizeValue = 0L;
                      long lastModifiedAt;

                      try {
                        BasicFileAttributes attrs =
                            Files.readAttributes(path, BasicFileAttributes.class);

                        lastModifiedAt = attrs.lastModifiedTime().toMillis();

                        if (!isDirectory) {
                          sizeValue = attrs.size();
                        } else if (eagerSizes) {
                          sizeValue = calculateDirectorySize(path);
                        } else {
                          sizeValue = DEFERRED_SIZE;
                        }
                      } catch (IOException e) {
                        throw new FileAccessException(
                            "Failed to read file attributes: "
                                + path
                                + " with exception: "
                                + e.getMessage());
                      }

                      return new FolderContentItemDto(
                          fileName,
                          itemRelativePath,
                          isDirectory,
                          sizeValue,
                          null,
                          lastModifiedAt,
                          conflict,
                          originalPath,
                          duplicate);
                    } catch (IOException e) {
                      throw new InvalidPathException(
                          "Invalid path detected while listing: " + path + " - " + e.getMessage());
                    }
                  })
              .filter(Objects::nonNull)
              .collect(Collectors.toList());
    }

    applySorting(items, sort);

    long totalElements = items.size();
    int totalPages = (int) Math.ceil(totalElements / (double) size);

    int fromIndex = page * size;
    int toIndex = Math.min(fromIndex + size, items.size());

    List<FolderContentItemDto> pageContent =
        fromIndex >= items.size() ? List.of() : items.subList(fromIndex, toIndex);

    markDuplicatesForPage(rootReal, pageContent);

    resolveDeferredAttributes(folder, pageContent, includeDirectorySizes || eagerSizes);

    return new PageResponseDto<>(pageContent, totalElements, totalPages, page);
  }

  public long getDirectorySize(String rootPath, String relativePath) throws IOException {
    Path folder =
        (relativePath == null || relativePath.isBlank())
            ? Paths.get(rootPath)
            : Paths.get(rootPath, relativePath).normalize();

    FileUtils.validatePath(rootPath, folder);
    if (!Files.exists(folder) || !Files.isDirectory(folder)) {
      throw new InvalidPathException("Folder does not exist or is not a directory: " + folder);
    }

    return calculateDirectorySize(folder);
  }

  private void resolveDeferredAttributes(
      Path folder, List<FolderContentItemDto> items, boolean resolveDirectorySizes)
      throws IOException {
    for (FolderContentItemDto item : items) {
      Path path = folder.resolve(item.getName());
      if (item.isDirectory()) {
        if (resolveDirectorySizes && item.getSize() == DEFERRED_SIZE) {
          item.setSize(calculateDirectorySize(path));
        }
      } else {
        item.setMimeType(Files.probeContentType(path));
      }
    }
  }

  private boolean sortsBySize(String sort) {
    return sort != null && !sort.isBlank() && "size".equals(sort.split(",")[0]);
  }

  /**
   * Calculates the total size of a directory by recursively summing the sizes of all regular files
   * it contains. Individual files whose size cannot be read are skipped so that a single
   * inaccessible file does not fail the whole listing.
   *
   * <p>Symbolic links are not followed, so linked files are not counted and targets outside the
   * directory tree are ignored.
   *
   * @param directory the directory whose cumulative size should be calculated
   * @return the total size in bytes of all regular files contained in {@code directory}
   * @throws FileAccessException if the directory tree cannot be traversed
   */
  private long calculateDirectorySize(Path directory) {
    try (var stream = Files.walk(directory)) {
      return stream
          .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .mapToLong(
              file -> {
                try {
                  return Files.readAttributes(
                          file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                      .size();
                } catch (IOException e) {
                  return 0L;
                }
              })
          .sum();
    } catch (IOException e) {
      throw new FileAccessException("Failed to calculate directory size: " + directory, e);
    }
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
      case "size":
        comparator = Comparator.comparingLong(FolderContentItemDto::getSize);
        break;
      case "lastModifiedAt":
        comparator = Comparator.comparingLong(FolderContentItemDto::getLastModifiedAt);
        break;
      case "name":
      default:
        comparator =
            Comparator.comparing(FolderContentItemDto::getName, String.CASE_INSENSITIVE_ORDER);
        break;
    }

    if (!ascending) {
      comparator = comparator.reversed();
    }

    // Break ties by name so equal primary keys (folders all report size 0, and timestamps can
    // collide) keep a deterministic order. Names are unique within a folder, so this yields a
    // total ordering and stops items from being dropped or duplicated across paged requests.
    comparator = comparator.thenComparing(FolderContentItemDto::getName);

    items.sort(comparator);
  }

  private FolderDto readFolderShallow(String rootPath, Path path, boolean includeChildCounts)
      throws IOException {
    // Resolve root path once for relative path calculation and path validation
    Path rootReal = Paths.get(rootPath).toRealPath().normalize();
    Path folderPathReal = FileUtils.validateAndResolvePathWithinRoot(rootReal, path);

    List<FolderListItemDto> subfolders = new ArrayList<>();
    List<FileDto> files = new ArrayList<>();
    Path trashDir = Paths.get(rootPath, FileUtils.TRASH_DIR).normalize();
    try (var stream = Files.list(path)) {
      stream
          .filter(childPath -> !childPath.normalize().equals(trashDir))
          .forEach(
              childPath -> {
                if (Files.isSymbolicLink(childPath)) {
                  return;
                }
                try {
                  // Resolve and validate once per child to avoid repeated toRealPath() calls.
                  Path childPathReal =
                      FileUtils.validateAndResolvePathWithinRoot(rootReal, childPath);

                  if (Files.isDirectory(childPath)) {
                    subfolders.add(
                        toFolderListItemDto(
                            rootReal, childPath, childPathReal, includeChildCounts));
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
      String fileName = filePath.getFileName().toString();
      boolean conflict = isSyncthingConflict(fileName);

      String originalPath = null;

      if (conflict) {
        String originalFileName = inferOriginalFileName(fileName);
        Path originalFile = filePath.resolveSibling(originalFileName);

        if (Files.isRegularFile(originalFile, LinkOption.NOFOLLOW_LINKS)) {
          Path originalFileReal =
              FileUtils.validateAndResolvePathWithinRoot(rootReal, originalFile);
          originalPath = toRelativePath(rootReal, originalFileReal);
        }
      }

      BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
      return new FileDto(
          fileName,
          relativePath,
          attrs.size(),
          Files.probeContentType(filePath),
          attrs.lastModifiedTime().toMillis(),
          conflict,
          originalPath);

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
                  FileUtils.validatePath(rootReal, childPath);
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

  /**
   * Resolves and validates a folder before it is used as the source of a streamed archive.
   *
   * @param rootPath the authenticated user's root folder
   * @param relativeFolderPath the folder path relative to the user's root
   * @return the real path of the requested folder
   * @throws IOException if the path cannot be resolved
   * @throws InvalidPathException if the path escapes the root, addresses trash, or is not a folder
   */
  public Path resolveArchiveFolder(String rootPath, String relativeFolderPath) throws IOException {
    Path rootReal = Paths.get(rootPath).toRealPath().normalize();
    Path relative;
    try {
      relative =
          relativeFolderPath == null || relativeFolderPath.isBlank()
              ? Path.of("")
              : Path.of(relativeFolderPath);
    } catch (java.nio.file.InvalidPathException exception) {
      throw new InvalidPathException("Invalid folder path: " + relativeFolderPath);
    }
    boolean addressesTrash = false;
    for (Path part : relative) {
      if (FileUtils.TRASH_DIR.equals(part.toString())) {
        addressesTrash = true;
        break;
      }
    }
    if (relative.isAbsolute() || addressesTrash) {
      throw new InvalidPathException("Invalid folder path: " + relativeFolderPath);
    }

    Path folderReal =
        FileUtils.validateAndResolvePathWithinRoot(
            rootReal, rootReal.resolve(relative).normalize());
    if (!Files.isDirectory(folderReal)) {
      throw new InvalidPathException("Folder does not exist or is not a directory: " + relative);
    }
    return folderReal;
  }

  /**
   * Streams a folder's contents as ZIP entries without following symbolic links.
   *
   * <p>Entry names are relative to the selected folder. Empty directories are retained and any
   * directory named {@code .trash} is excluded.
   */
  public void writeFolderArchive(String rootPath, Path folder, OutputStream output)
      throws IOException {
    Path rootReal = Paths.get(rootPath).toRealPath().normalize();
    Path folderReal = FileUtils.validateAndResolvePathWithinRoot(rootReal, folder);
    if (!Files.isDirectory(folderReal)) {
      throw new InvalidPathException("Folder does not exist or is not a directory: " + folder);
    }

    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      Files.walkFileTree(
          folderReal,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                throws IOException {
              if (!directory.equals(folderReal)
                  && FileUtils.TRASH_DIR.equals(directory.getFileName().toString())) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              if (!directory.equals(folderReal)) {
                zip.putNextEntry(new ZipEntry(archiveEntryName(folderReal, directory) + "/"));
                zip.closeEntry();
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                return FileVisitResult.CONTINUE;
              }

              try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                Path fileReal = file.toRealPath().normalize();
                if (!fileReal.startsWith(rootReal)) {
                  throw new InvalidPathException("Path traversal attempt detected: " + file);
                }
                zip.putNextEntry(new ZipEntry(archiveEntryName(folderReal, file)));
                input.transferTo(zip);
                zip.closeEntry();
              }
              return FileVisitResult.CONTINUE;
            }
          });
      zip.finish();
    }
  }

  private String archiveEntryName(Path folder, Path entry) {
    return folder.relativize(entry).toString().replace('\\', '/');
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
