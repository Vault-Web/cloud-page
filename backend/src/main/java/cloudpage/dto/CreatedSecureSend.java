package cloudpage.dto;

import cloudpage.model.SecureSend;

/** Internal creation result; the raw token is returned once and is never persisted. */
public record CreatedSecureSend(SecureSend secureSend, String token) {}
