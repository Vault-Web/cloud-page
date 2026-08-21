package cloudpage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateCommentRequest {
  @NotBlank private String ownerUsername;

  @NotBlank private String filePath;

  @NotBlank
  @Size(max = 8192)
  private String content;
}
