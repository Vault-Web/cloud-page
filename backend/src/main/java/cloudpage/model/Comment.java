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

/** Entity representing a comment left on a file or folder in a user's cloud storage. */
@Entity
@Table(
    name = "comments",
    indexes = {
      @Index(name = "idx_comment_owner_path", columnList = "owner_username, file_path"),
      @Index(name = "idx_comment_created_at", columnList = "created_at")
    })
@Getter
@Setter
public class Comment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "owner_username", nullable = false)
  private String ownerUsername;

  @Column(name = "file_path", nullable = false, length = 4096)
  private String filePath;

  @Column(name = "author_username", nullable = false)
  private String authorUsername;

  @Column(nullable = false, length = 8192)
  private String content;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
