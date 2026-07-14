package io.floci.oci.core.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The OCI wire error body: {@code {"code": "...", "message": "..."}}.
 * The {@code opc-request-id} travels as a response header, not in the body.
 */
@RegisterForReflection
public record OciErrorResponse(
        @JsonProperty("code") String code,
        @JsonProperty("message") String message
) {
}
