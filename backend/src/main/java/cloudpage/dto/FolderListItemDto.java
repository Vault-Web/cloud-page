package cloudpage.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class FolderListItemDto {
  private String name;
  private String path;
  private long directChildrenCount;
  private long lastModifiedAt;
}
