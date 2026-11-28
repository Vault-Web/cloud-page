package cloudpage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class PageResponseDto<T> {

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
  private List<T> content;

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
  private long totalElements;

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
  private int totalPages;

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
  private int pageNumber;
}
