package cloudpage.service;

import cloudpage.dto.DuplicateFileDto;
import cloudpage.dto.FolderContentItemDto;
import cloudpage.exceptions.InvalidPathException;
import cloudpage.util.ChecksumUtils;
import cloudpage.util.FileUtils;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Best-effort, per-request duplicate hints. No cross-request checksums or directory indexes. */
class DuplicateDetector {
  private final int maxEntries;
  private final int maxHashes;
  private final long maxBytes;

  DuplicateDetector() {
    this(10_000, 1_000, 64L * 1024 * 1024);
  }

  DuplicateDetector(int maxEntries, int maxHashes, long maxBytes) {
    if (maxEntries < 1 || maxHashes < 1 || maxBytes < 1) {
      throw new IllegalArgumentException("Duplicate scan limits must be positive");
    }
    this.maxEntries = maxEntries;
    this.maxHashes = maxHashes;
    this.maxBytes = maxBytes;
  }

  private static class ScanBudget {
    int entries;
    int hashes;
    long bytes;
  }

  List<DuplicateFileDto> findDuplicates(Path rootPath, Path target, String checksum, long size)
      throws IOException {
    if (size == 0) return List.of();
    Path root = rootPath.toRealPath().normalize();
    Path targetReal = FileUtils.validateAndResolvePathWithinRoot(root, target);
    ScanBudget budget = new ScanBudget();
    List<DuplicateFileDto> duplicates = new ArrayList<>();
    for (Path candidate : candidates(root, Set.of(size), budget).getOrDefault(size, List.of())) {
      if (candidate.equals(targetReal)) continue;
      if (checksum.equals(checksum(root, candidate, budget))) {
        duplicates.add(
            new DuplicateFileDto(
                candidate.getFileName().toString(),
                root.relativize(candidate).toString().replace('\\', '/')));
      }
    }
    duplicates.sort(Comparator.comparing(DuplicateFileDto::getFilePath));
    return duplicates;
  }

  void markDuplicates(Path root, Path folder, List<FolderContentItemDto> items) throws IOException {
    Path folderReal = folder.toRealPath().normalize();
    Map<Path, FolderContentItemDto> pageFiles = new HashMap<>();
    Set<Long> sizes = new HashSet<>();
    for (FolderContentItemDto item : items) {
      if (item.isDirectory() || item.getSize() <= 0) continue;
      Path path = folderReal.resolve(item.getName());
      // Internal symlink aliases remain visible in listings, but are not duplicate candidates.
      if (Files.isSymbolicLink(path)) continue;
      pageFiles.put(path, item);
      sizes.add(item.getSize());
    }
    if (sizes.isEmpty()) return;
    ScanBudget budget = new ScanBudget();
    for (List<Path> sameSize : candidates(root, sizes, budget).values()) {
      // A file whose size is unique cannot have a duplicate, so do not read its contents.
      if (sameSize.size() < 2) continue;
      Map<String, List<Path>> byChecksum = new HashMap<>();
      for (Path candidate : sameSize) {
        String checksum = checksum(root, candidate, budget);
        if (checksum != null) {
          byChecksum.computeIfAbsent(checksum, key -> new ArrayList<>()).add(candidate);
        }
      }
      for (List<Path> matches : byChecksum.values()) {
        if (matches.size() < 2) continue;
        for (Path match : matches) {
          FolderContentItemDto item = pageFiles.get(match);
          if (item != null) item.setDuplicate(true);
        }
      }
    }
  }

  private Map<Long, List<Path>> candidates(Path root, Set<Long> sizes, ScanBudget budget)
      throws IOException {
    Map<Long, List<Path>> bySize = new HashMap<>();
    Path trash = root.resolve(FileUtils.TRASH_DIR);
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
            if (++budget.entries >= maxEntries) return FileVisitResult.TERMINATE;
            return directory.startsWith(trash)
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            budget.entries++;
            if (attrs.isRegularFile() && attrs.size() > 0 && sizes.contains(attrs.size())) {
              bySize.computeIfAbsent(attrs.size(), key -> new ArrayList<>()).add(file);
            }
            return budget.entries >= maxEntries
                ? FileVisitResult.TERMINATE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exception) {
            return ++budget.entries >= maxEntries
                ? FileVisitResult.TERMINATE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException exception) {
            return FileVisitResult.CONTINUE;
          }
        });
    return bySize;
  }

  private String checksum(Path root, Path path, ScanBudget budget) {
    try {
      BasicFileAttributes before =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!before.isRegularFile()
          || budget.hashes >= maxHashes
          || before.size() > maxBytes - budget.bytes) return null;
      Path real = FileUtils.validateAndResolvePathWithinRoot(root, path);
      if (!real.equals(path)) return null;
      String result;
      try (var input = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS)) {
        budget.hashes++;
        var digest = ChecksumUtils.newDigest("SHA-256");
        byte[] buffer = new byte[8192];
        long remaining = before.size();
        // Never read beyond the observed size: a growing file cannot escape the byte budget.
        while (remaining > 0) {
          int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
          if (read < 0) return null;
          budget.bytes += read;
          remaining -= read;
          digest.update(buffer, 0, read);
        }
        result = HexFormat.of().formatHex(digest.digest());
      }
      BasicFileAttributes after =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      // A changed or replaced candidate is not a trustworthy match for this request.
      return after.isRegularFile()
              && before.size() == after.size()
              && before.lastModifiedTime().equals(after.lastModifiedTime())
              && Objects.equals(before.fileKey(), after.fileKey())
          ? result
          : null;
    } catch (IOException | InvalidPathException exception) {
      return null;
    }
  }
}
