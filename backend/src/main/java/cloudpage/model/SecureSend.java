package cloudpage.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** An expiring external download link for one file owned by a Cloud Page user. */
@Entity
@Table(
    name = "secure_sends",
    indexes = {
      @Index(name = "idx_secure_send_owner", columnList = "owner_id"),
      @Index(name = "idx_secure_send_token_hash", columnList = "token_hash", unique = true)
    })
@Getter
@Setter
public class SecureSend {

  @Id private String id;

  @Column(name = "owner_id", nullable = false)
  private String ownerId;

  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  @Column(nullable = false, length = 4096)
  private String relativeFilePath;

  @Column(nullable = false)
  private String displayName;

  private String passwordHash;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant expiresAt;

  private Instant revokedAt;
}
