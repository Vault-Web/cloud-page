package cloudpage.dto;

import cloudpage.model.SharePermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateResourceShareRequest {

  @NotBlank private String path;

  @NotBlank private String recipientUsername;

  @NotEmpty private Set<SharePermission> permissions;
}
