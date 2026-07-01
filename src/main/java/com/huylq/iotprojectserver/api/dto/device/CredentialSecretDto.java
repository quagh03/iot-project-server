package com.huylq.iotprojectserver.api.dto.device;

import java.time.OffsetDateTime;

/**
 * The one-time credential response (OpenAPI {@code CredentialSecret}).
 *
 * <p>{@code clientSecret} is returned <b>exactly once</b> on issue / rotation and
 * is never persisted in plaintext nor re-emitted by any read endpoint. After a
 * rotation, {@code graceExpiresAt} is the instant the previous secret stops working.
 */
public record CredentialSecretDto(
    String clientId,
    String clientSecret,
    OffsetDateTime rotatedAt,
    OffsetDateTime graceExpiresAt) {
}
