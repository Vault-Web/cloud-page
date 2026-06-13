package cloudpage.dto;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class TrashEntryDto {

  private String id;
  private String name;
  private String originalPath;
  private Instant deletedAt;
  private long size;
}
