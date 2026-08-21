package cloudpage.dto;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class CommentDto {
  private Long id;
  private String ownerUsername;
  private String filePath;
  private String authorUsername;
  private String content;
  private Instant createdAt;
}
