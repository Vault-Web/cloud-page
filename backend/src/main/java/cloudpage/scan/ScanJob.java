package cloudpage.scan;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mutable state of a single asynchronous folder scan, owned by one user. Only infected and errored
 * files are retained in {@link #findings} (a clean scan of a large folder therefore stays small);
 * overall counts are tracked separately.
 */
public class ScanJob {

  private final String id;
  private final String userId;
  private final String path;
  private final Instant createdAt;

  private volatile ScanStatus status = ScanStatus.PENDING;
  private volatile Instant finishedAt;
  private volatile String error;

  private final AtomicInteger filesScanned = new AtomicInteger();
  private final AtomicInteger infectedCount = new AtomicInteger();
  private final List<FileScanResult> findings = new CopyOnWriteArrayList<>();

  public ScanJob(String id, String userId, String path, Instant createdAt) {
    this.id = id;
    this.userId = userId;
    this.path = path;
    this.createdAt = createdAt;
  }

  public void recordResult(FileScanResult result) {
    filesScanned.incrementAndGet();
    if (result.verdict() == ScanVerdict.INFECTED) {
      infectedCount.incrementAndGet();
      findings.add(result);
    } else if (result.verdict() == ScanVerdict.ERROR) {
      findings.add(result);
    }
  }

  public String getId() {
    return id;
  }

  public String getUserId() {
    return userId;
  }

  public String getPath() {
    return path;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public ScanStatus getStatus() {
    return status;
  }

  public void setStatus(ScanStatus status) {
    this.status = status;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public void setFinishedAt(Instant finishedAt) {
    this.finishedAt = finishedAt;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }

  public int getFilesScanned() {
    return filesScanned.get();
  }

  public int getInfectedCount() {
    return infectedCount.get();
  }

  public List<FileScanResult> getFindings() {
    return List.copyOf(findings);
  }
}
