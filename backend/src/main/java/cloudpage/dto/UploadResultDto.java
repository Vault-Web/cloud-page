package cloudpage.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class UploadResultDto {

  private boolean duplicate;
  private List<DuplicateFileDto> duplicates;
}
