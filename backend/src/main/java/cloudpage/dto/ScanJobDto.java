package cloudpage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/** API view of an asynchronous folder scan job: status, metadata, and any threats found. */
@Getter
@Setter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScanJobDto {
  private String jobId;
  private String path;
  private String status;
  private Instant createdAt;
  private Instant finishedAt;
  private int filesScanned;
  private int infectedCount;
  private String error;
  private List<FileScanResultDto> findings;
}
