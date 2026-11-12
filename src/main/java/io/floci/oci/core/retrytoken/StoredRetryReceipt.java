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
 * @param createdId   the OCID the request created (its body's {@code id}, or its work request's CREATED
 *                    resource), or null when it created none that floci-oci can name
 * @param invalidated true once that resource was deleted: a retry is then
 *                    {@code 409 InvalidatedRetryToken} instead of a replay
 * @param deletionWorkRequestId the work request of an accepted delete of that resource whose outcome was
 *                    not known yet; a retry checks it, and only a SUCCEEDED delete invalidates the token
 * @param expiresAt   24 hours after the first use; the deadline does not slide
 */
@RegisterForReflection
public record StoredRetryReceipt(
        String fingerprint,
        int status,
        String contentType,
        Map<String, List<String>> headers,
        String body,
        String createdId,
        boolean invalidated,
        String deletionWorkRequestId,
        Instant createdAt,
        Instant expiresAt
) {

    StoredRetryReceipt invalidate() {
        return new StoredRetryReceipt(fingerprint, status, contentType, headers, body, createdId, true, null,
                createdAt, expiresAt);
    }

    StoredRetryReceipt deletionPending(String workRequestId) {
        return new StoredRetryReceipt(fingerprint, status, contentType, headers, body, createdId, false,
                workRequestId, createdAt, expiresAt);
    }

    /** A delete was invalidated outright (null work request) or is pending on its work request. */
    StoredRetryReceipt deleted(String workRequestId) {
        return workRequestId == null ? invalidate() : deletionPending(workRequestId);
    }
}
