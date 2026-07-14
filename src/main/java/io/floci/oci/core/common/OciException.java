package io.floci.oci.core.common;

/**
 * Domain exception carrying the OCI service error code and HTTP status.
 * Rendered on the wire by {@link OciExceptionMapper} as
 * {@code {"code": "...", "message": "..."}} plus the {@code opc-request-id} header.
 *
 * <p>OCI SDKs retry 429/500/502/503/504 by default — do not emit those statuses casually.
 */
public class OciException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public OciException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public static OciException notAuthenticated(String message) {
        return new OciException("NotAuthenticated", message, 401);
    }

    /** OCI deliberately reports missing resources and denied access identically. */
    public static OciException notAuthorizedOrNotFound(String message) {
        return new OciException("NotAuthorizedOrNotFound", message, 404);
    }

    public static OciException invalidParameter(String message) {
        return new OciException("InvalidParameter", message, 400);
    }

    public static OciException missingParameter(String message) {
        return new OciException("MissingParameter", message, 400);
    }

    public static OciException conflict(String message) {
        return new OciException("Conflict", message, 409);
    }

    public static OciException noEtagMatch(String message) {
        return new OciException("NoEtagMatch", message, 412);
    }

    public static OciException ifNoneMatchFailed(String message) {
        return new OciException("IfNoneMatchFailed", message, 412);
    }

    public static OciException limitExceeded(String message) {
        return new OciException("LimitExceeded", message, 400);
    }

    public static OciException tooManyRequests(String message) {
        return new OciException("TooManyRequests", message, 429);
    }

    public static OciException methodNotAllowed(String message) {
        return new OciException("MethodNotAllowed", message, 405);
    }

    public static OciException internalServerError(String message) {
        return new OciException("InternalServerError", message, 500);
    }

    public static OciException serviceUnavailable(String message) {
        return new OciException("ServiceUnavailable", message, 503);
    }

    public static OciException notImplemented(String message) {
        return new OciException("NotImplemented", message, 501);
    }

    // Object Storage specific codes

    public static OciException namespaceNotFound(String message) {
        return new OciException("NamespaceNotFound", message, 404);
    }

    public static OciException bucketNotFound(String message) {
        return new OciException("BucketNotFound", message, 404);
    }

    public static OciException objectNotFound(String message) {
        return new OciException("ObjectNotFound", message, 404);
    }

    public static OciException bucketAlreadyExists(String message) {
        return new OciException("BucketAlreadyExists", message, 409);
    }
}
