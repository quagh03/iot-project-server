package com.huylq.iotprojectserver.api.dto.device;

import java.time.OffsetDateTime;

/**
 * Safe credential view (OpenAPI {@code CredentialMetadata}) — never includes the secret.
 */
public record CredentialMetadataDto(
    String clientId,
    OffsetDateTime rotatedAt) {
}
