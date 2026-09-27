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
public class NotificationDto {
  private Long id;
  private String recipientUsername;
  private String type;
  private String message;
  private String filePath;
  private String ownerUsername;
  private boolean read;
  private Instant createdAt;
}
