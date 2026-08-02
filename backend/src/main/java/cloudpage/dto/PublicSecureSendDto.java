package cloudpage.dto;

import cloudpage.model.SharedResourceType;
import java.time.Instant;

/**
 * What a share link may reveal before the password is known: enough for the public landing page to
 * describe what is on offer, never the location of the resource inside the owner's storage. Folder
 * links carry no size, which would require walking the subtree.
 */
public record PublicSecureSendDto(
    String fileName,
    long sizeBytes,
    Instant expiresAt,
    boolean passwordProtected,
    SharedResourceType resourceType) {}
