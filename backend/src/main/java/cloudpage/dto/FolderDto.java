package cloudpage.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class FolderDto {
  private String name;
  private String path;
  private List<FolderDto> folders;
  private List<FileDto> files;
}
