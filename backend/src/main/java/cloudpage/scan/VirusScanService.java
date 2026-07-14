package cloudpage.scan;

import cloudpage.exceptions.InvalidPathException;
import cloudpage.exceptions.ResourceNotFoundException;
import cloudpage.service.FolderService;
import cloudpage.service.TrashService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Runs asynchronous virus scans over a user's folder. A scan is submitted with {@link #startScan}
 * (returning immediately with a {@link ScanJob} in state {@link ScanStatus#PENDING}); the walk and
 * per-file scanning then run on a background executor. Callers poll {@link #getJob} for progress
 * and results. All paths are confined to the caller's root directory and jobs are only visible to
 * the user that created them.
 */
@Service
public class VirusScanService {

  private static final Logger log = LoggerFactory.getLogger(VirusScanService.class);

  private final VirusScanner scanner;
  private final FolderService folderService;
  private final Executor scanExecutor;
  private final VirusScanProperties properties;
  private final Map<String, ScanJob> jobs = new ConcurrentHashMap<>();

  public VirusScanService(
      VirusScanner scanner,
      FolderService folderService,
      @Qualifier("virusScanExecutor") Executor scanExecutor,
      VirusScanProperties properties) {
    this.scanner = scanner;
    this.folderService = folderService;
    this.scanExecutor = scanExecutor;
    this.properties = properties;
  }

  /**
   * Validates the target folder and submits an asynchronous scan.
   *
   * @param rootPath the caller's root directory (security boundary)
   * @param userId the id of the owning user
   * @param relativePath the folder to scan, relative to the root (blank means the whole root)
   * @return the newly created job; it starts in {@link ScanStatus#PENDING} but the background
   *     executor may already have advanced it to {@link ScanStatus#RUNNING} by the time this
   *     returns
   * @throws IOException if the path cannot be resolved
   * @throws InvalidPathException if the folder is outside the root or is not a directory
   */
  public ScanJob startScan(String rootPath, String userId, String relativePath) throws IOException {
    String relative = relativePath == null ? "" : relativePath;
    Path folder =
        relative.isBlank() ? Paths.get(rootPath) : Paths.get(rootPath, relative).normalize();
    folderService.validatePath(rootPath, folder);
    if (!Files.exists(folder) || !Files.isDirectory(folder)) {
      throw new InvalidPathException("Folder does not exist or is not a directory: " + relative);
    }

    ScanJob job = new ScanJob(UUID.randomUUID().toString(), userId, relative, Instant.now());
    jobs.put(job.getId(), job);
    scanExecutor.execute(() -> runScan(job, folder, rootPath));
    return job;
  }

  /**
   * Returns a job owned by the given user.
   *
   * @throws ResourceNotFoundException if the job does not exist or belongs to another user
   */
  public ScanJob getJob(String jobId, String userId) {
    ScanJob job = jobs.get(jobId);
    if (job == null || !job.getUserId().equals(userId)) {
      throw new ResourceNotFoundException("ScanJob", "id", jobId);
    }
    return job;
  }

  /**
   * Periodically drops finished (completed or failed) jobs older than the configured retention so
   * the in-memory job map does not grow without bound on a long-lived instance.
   */
  @Scheduled(fixedDelayString = "${cloudpage.virus-scan.eviction-interval-ms:600000}")
  public void evictExpiredJobs() {
    Instant cutoff = Instant.now().minus(Duration.ofMinutes(properties.getRetentionMinutes()));
    jobs.values()
        .removeIf(
            job -> {
              Instant finished = job.getFinishedAt();
              return finished != null
                  && finished.isBefore(cutoff)
                  && (job.getStatus() == ScanStatus.COMPLETED
                      || job.getStatus() == ScanStatus.FAILED);
            });
  }

  private void runScan(ScanJob job, Path folder, String rootPath) {
    job.setStatus(ScanStatus.RUNNING);
    Path rootBase = Paths.get(rootPath);
    Path trashDir = Paths.get(rootPath, TrashService.TRASH_DIR).normalize();
    try (var stream = Files.walk(folder)) {
      stream
          // Do not follow symlinks: a symlink inside the root could otherwise point at (and stream
          // to the scanner) a host file outside the user's boundary.
          .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
          .filter(p -> !p.normalize().startsWith(trashDir))
          .forEach(file -> job.recordResult(scanFile(rootBase, file)));
      job.setStatus(ScanStatus.COMPLETED);
    } catch (Exception e) {
      log.warn("Folder scan {} failed: {}", job.getId(), e.getMessage());
      job.setStatus(ScanStatus.FAILED);
      job.setError("Scan failed");
    } finally {
      job.setFinishedAt(Instant.now());
    }
  }

  private FileScanResult scanFile(Path rootBase, Path file) {
    String relativePath = toRelativePath(rootBase, file);
    try {
      VirusScanner.ScanResult result = scanner.scan(file);
      return switch (result.verdict()) {
        case CLEAN -> new FileScanResult(relativePath, ScanVerdict.CLEAN, null);
        case INFECTED -> FileScanResult.infected(relativePath, result.threat());
        case ERROR -> FileScanResult.error(relativePath, result.threat());
      };
    } catch (Exception e) {
      // A single unscannable file should not abort the whole folder scan.
      return FileScanResult.error(relativePath, "could not scan file");
    }
  }

  private static String toRelativePath(Path rootBase, Path file) {
    try {
      return rootBase
          .toAbsolutePath()
          .normalize()
          .relativize(file.toAbsolutePath().normalize())
          .toString()
          .replace('\\', '/');
    } catch (IllegalArgumentException e) {
      return file.getFileName().toString();
    }
  }
}
