package cloudpage.dto;

import cloudpage.model.SharePermission;
import cloudpage.model.SharedResourceType;
import java.time.Instant;
import java.util.Set;

public record ResourceShareDto(
    String id,
    String ownerUsername,
    String recipientUsername,
    String displayName,
    SharedResourceType resourceType,
    Set<SharePermission> permissions,
    Instant createdAt,
    boolean revoked) {}
