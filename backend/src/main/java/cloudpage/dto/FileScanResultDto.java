package cloudpage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/** A single infected or errored file within a scan result. */
@Getter
@Setter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileScanResultDto {
  private String path;
  private String verdict;
  private String detail;
}
