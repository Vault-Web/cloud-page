package cloudpage.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class FolderContentItemDto {

  private String name;
  private String path;
  private boolean directory;
  private long size;
  private String mimeType;
}
