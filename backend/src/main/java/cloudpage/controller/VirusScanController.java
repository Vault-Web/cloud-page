package cloudpage.controller;

import cloudpage.dto.FileScanResultDto;
import cloudpage.dto.ScanJobDto;
import cloudpage.scan.ScanJob;
import cloudpage.scan.VirusScanService;
import cloudpage.service.UserService;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints for asynchronously scanning a user's folder for viruses. {@code POST} starts a scan and
 * returns a job id; {@code GET} polls the job for status and any detected threats. Both are scoped
 * to the authenticated user's root folder.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/files/scan")
public class VirusScanController {

  private final VirusScanService virusScanService;
  private final UserService userService;

  @PostMapping
  public ResponseEntity<ScanJobDto> startScan(
      @RequestParam(required = false, defaultValue = "") String path) throws IOException {
    var user = userService.getCurrentUser();
    ScanJob job = virusScanService.startScan(user.getRootFolderPath(), user.getId(), path);
    return ResponseEntity.accepted().body(toDto(job));
  }

  @GetMapping("/{jobId}")
  public ScanJobDto getScan(@PathVariable String jobId) {
    var user = userService.getCurrentUser();
    return toDto(virusScanService.getJob(jobId, user.getId()));
  }

  private ScanJobDto toDto(ScanJob job) {
    List<FileScanResultDto> findings =
        job.getFindings().stream()
            .map(f -> new FileScanResultDto(f.path(), f.verdict().name(), f.detail()))
            .collect(Collectors.toList());
    return new ScanJobDto(
        job.getId(),
        job.getPath(),
        job.getStatus().name(),
        job.getCreatedAt(),
        job.getFinishedAt(),
        job.getFilesScanned(),
        job.getInfectedCount(),
        job.getError(),
        findings);
  }
}
