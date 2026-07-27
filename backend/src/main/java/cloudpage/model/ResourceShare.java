package cloudpage.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/** An authenticated grant from one user to another for exactly one file or folder. */
@Entity
@Table(
    name = "resource_shares",
    indexes = {
      @Index(name = "idx_resource_share_owner", columnList = "owner_id"),
      @Index(name = "idx_resource_share_recipient", columnList = "recipient_id")
    })
@Getter
@Setter
public class ResourceShare {

  @Id private String id;

  @Column(name = "owner_id", nullable = false)
  private String ownerId;

  @Column(name = "recipient_id", nullable = false)
  private String recipientId;

  @Column(name = "relative_path", nullable = false, length = 4096)
  private String relativePath;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(name = "resource_type", nullable = false)
  private SharedResourceType resourceType;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "resource_share_permissions",
      joinColumns = @JoinColumn(name = "share_id"))
  @Column(name = "permission", nullable = false)
  @Enumerated(EnumType.STRING)
  private Set<SharePermission> permissions = new HashSet<>();

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;
}
