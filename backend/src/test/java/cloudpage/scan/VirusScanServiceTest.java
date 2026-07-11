package cloudpage.scan;

import static org.junit.jupiter.api.Assertions.*;

import cloudpage.exceptions.InvalidPathException;
import cloudpage.exceptions.ResourceNotFoundException;
import cloudpage.service.FolderService;
import cloudpage.service.TrashService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VirusScanServiceTest {

  @TempDir Path tempDir;

  private VirusScanService service;

  /** Runs submitted tasks inline so a scan is finished by the time {@code startScan} returns. */
  private static final Executor SYNC_EXECUTOR = Runnable::run;

  /** Fake scanner: files named *virus* are infected, *bad* error, everything else clean. */
  private static final VirusScanner FAKE_SCANNER =
      file -> {
        String name = file.getFileName().toString();
        if (name.contains("virus")) {
          return VirusScanner.ScanResult.infected("Test-Threat");
        }
        if (name.contains("bad")) {
          return VirusScanner.ScanResult.error("boom");
        }
        return VirusScanner.ScanResult.clean();
      };

  @BeforeEach
  void setUp() {
    service = new VirusScanService(FAKE_SCANNER, new FolderService(), SYNC_EXECUTOR);
  }

  @Test
  void startScan_mixedFolder_completesWithCountsAndFindings() throws IOException {
    Files.writeString(tempDir.resolve("clean.txt"), "safe");
    Files.writeString(tempDir.resolve("virus.txt"), "evil");

    ScanJob job = service.startScan(tempDir.toString(), "user-1", "");

    assertEquals(ScanStatus.COMPLETED, job.getStatus());
    assertEquals(2, job.getFilesScanned());
    assertEquals(1, job.getInfectedCount());
    assertEquals(1, job.getFindings().size());
    FileScanResult finding = job.getFindings().get(0);
    assertEquals(ScanVerdict.INFECTED, finding.verdict());
    assertEquals("virus.txt", finding.path());
    assertEquals("Test-Threat", finding.detail());
    assertNotNull(job.getFinishedAt());
  }

  @Test
  void startScan_errorFromScanner_recordedAsFindingButNotInfected() throws IOException {
    Files.writeString(tempDir.resolve("bad.txt"), "unscannable");

    ScanJob job = service.startScan(tempDir.toString(), "user-1", "");

    assertEquals(1, job.getFilesScanned());
    assertEquals(0, job.getInfectedCount());
    assertEquals(1, job.getFindings().size());
    assertEquals(ScanVerdict.ERROR, job.getFindings().get(0).verdict());
  }

  @Test
  void startScan_cleanFolder_hasNoFindings() throws IOException {
    Files.writeString(tempDir.resolve("a.txt"), "safe");
    Files.writeString(tempDir.resolve("b.txt"), "also safe");

    ScanJob job = service.startScan(tempDir.toString(), "user-1", "");

    assertEquals(2, job.getFilesScanned());
    assertTrue(job.getFindings().isEmpty());
  }

  @Test
  void startScan_excludesTrashDirectory() throws IOException {
    Files.writeString(tempDir.resolve("virus.txt"), "evil");
    Path trash = Files.createDirectory(tempDir.resolve(TrashService.TRASH_DIR));
    Files.writeString(trash.resolve("virus-in-trash.txt"), "evil");

    ScanJob job = service.startScan(tempDir.toString(), "user-1", "");

    // Only the file outside .trash is scanned.
    assertEquals(1, job.getFilesScanned());
    assertEquals(1, job.getInfectedCount());
  }

  @Test
  void startScan_pathTraversal_throwsInvalidPathException() {
    assertThrows(
        InvalidPathException.class,
        () -> service.startScan(tempDir.toString(), "user-1", "../../etc"));
  }

  @Test
  void startScan_nonExistentFolder_throwsInvalidPathException() {
    assertThrows(
        InvalidPathException.class,
        () -> service.startScan(tempDir.toString(), "user-1", "does-not-exist"));
  }

  @Test
  void getJob_wrongUser_throwsResourceNotFound() throws IOException {
    Files.writeString(tempDir.resolve("a.txt"), "safe");
    ScanJob job = service.startScan(tempDir.toString(), "owner", "");

    assertThrows(
        ResourceNotFoundException.class, () -> service.getJob(job.getId(), "someone-else"));
  }

  @Test
  void getJob_unknownId_throwsResourceNotFound() {
    assertThrows(ResourceNotFoundException.class, () -> service.getJob("no-such-job", "user-1"));
  }

  @Test
  void getJob_owner_returnsJob() throws IOException {
    Files.writeString(tempDir.resolve("a.txt"), "safe");
    ScanJob job = service.startScan(tempDir.toString(), "owner", "");

    assertSame(job, service.getJob(job.getId(), "owner"));
  }
}
