package cloudpage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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

  @Schema(types = {"string", "null"})
  private String mimeType;

  private long lastModifiedAt;
  private boolean conflict;

  @Schema(types = {"string", "null"})
  private String originalPath;

  private boolean duplicate;

  public FolderContentItemDto(
      String name,
      String path,
      boolean directory,
      long size,
      String mimeType,
      long lastModifiedAt) {
    this(name, path, directory, size, mimeType, lastModifiedAt, false, null, false);
  }

  public FolderContentItemDto(
      String name,
      String path,
      boolean directory,
      long size,
      String mimeType,
      long lastModifiedAt,
      boolean conflict,
      String originalPath) {
    this(name, path, directory, size, mimeType, lastModifiedAt, conflict, originalPath, false);
  }
}
