package cloudpage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * Integrity metadata for a stored file. The {@code match} field is only populated when the caller
 * supplies an expected checksum to verify against; otherwise it is omitted from the response.
 */
@Getter
@Setter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChecksumDto {
  private String algorithm;
  private String checksum;
  private Boolean match;
}
