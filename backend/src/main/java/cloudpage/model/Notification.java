package cloudpage.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Entity representing user notifications for mentions or other service events. */
@Entity
@Table(
    name = "notifications",
    indexes = {
      @Index(name = "idx_notification_recipient", columnList = "recipient_username"),
      @Index(name = "idx_notification_created_at", columnList = "created_at")
    })
@Getter
@Setter
public class Notification {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "recipient_username", nullable = false)
  private String recipientUsername;

  @Column(nullable = false)
  private String type;

  @Column(nullable = false, length = 4096)
  private String message;

  @Column(name = "file_path", nullable = false, length = 4096)
  private String filePath;

  @Column(name = "owner_username", nullable = false)
  private String ownerUsername;

  @Column(name = "is_read", nullable = false)
  private boolean read;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
