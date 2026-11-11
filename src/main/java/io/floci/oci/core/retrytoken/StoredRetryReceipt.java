package io.floci.oci.core.retrytoken;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The completed response of a request that carried an {@code opc-retry-token}, kept so a retry
 * with the same token gets the same answer instead of running the action again.
 *
 * @param fingerprint SHA-256 of the canonical request, to tell a retry from a different request
 * @param status      the HTTP status that was returned
 * @param contentType the response media type, or null for an empty body
 * @param headers     the response headers worth replaying ({@code etag}, {@code Location},
 *                    {@code opc-work-request-id}, ...), never {@code opc-request-id}
 * @param body        the serialized response body, base64-encoded, or null when there was none
 * @param expiresAt   24 hours after the first use; the deadline does not slide
 */
@RegisterForReflection
public record StoredRetryReceipt(
        String fingerprint,
        int status,
        String contentType,
        Map<String, List<String>> headers,
        String body,
        Instant createdAt,
        Instant expiresAt
) {
}
