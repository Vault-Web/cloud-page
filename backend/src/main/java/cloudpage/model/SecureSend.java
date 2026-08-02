package cloudpage.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * An expiring external link to one file or folder owned by a Cloud Page user. A folder link lets
 * the recipient browse the contents and take files out of it; nothing outside the shared resource
 * is ever reachable through the link.
 */
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

  /** Path of the shared file or folder, relative to the owner's root. */
  @Column(nullable = false, length = 4096)
  private String relativeFilePath;

  @Column(nullable = false)
  private String displayName;

  /**
   * What the link points at. Null on rows written before folder links existed; those are files, and
   * {@code resourceTypeOrFile()} is what callers should read.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "resource_type")
  private SharedResourceType resourceType;

  /** The link's resource type, treating the pre-folder-link default as a file. */
  public SharedResourceType resourceTypeOrFile() {
    return resourceType == null ? SharedResourceType.FILE : resourceType;
  }

  private String passwordHash;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant expiresAt;

  private Instant revokedAt;
}
