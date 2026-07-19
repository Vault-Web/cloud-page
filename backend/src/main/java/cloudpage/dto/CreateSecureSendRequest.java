package cloudpage.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateSecureSendRequest {

  @NotBlank private String filePath;

  @NotNull @Future private Instant expiresAt;

  @Size(max = 256)
  private String password;
}
