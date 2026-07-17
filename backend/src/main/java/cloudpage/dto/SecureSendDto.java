package cloudpage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SecureSendDto {

  private String id;
  private String url;
  private String fileName;
  private Instant createdAt;
  private Instant expiresAt;
  private boolean passwordProtected;
  private boolean revoked;
}
