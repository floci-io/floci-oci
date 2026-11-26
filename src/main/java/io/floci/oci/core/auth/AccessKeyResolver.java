package io.floci.oci.core.auth;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves an Amazon S3 Compatibility API access key (the {@code id} of an Identity Customer Secret
 * Key) to the tenancy and user that own it, so S3 requests partition like OCI-signed ones. The
 * SigV4 signature itself is never verified: a Customer Secret Key's secret is not stored, so there is
 * nothing to verify it with.
 */
public interface AccessKeyResolver {

    Optional<AuthContext> resolve(String accessKey);

    /**
     * OCI's S3 Compatibility API host, {@code <namespace>.compat.objectstorage.<region>.<domain>}
     * (Object/Tasks/s3compatibleapi.htm). Group 1 is the namespace.
     */
    Pattern S3_COMPAT_HOST = Pattern.compile("^([a-z0-9][a-z0-9_-]*)\\.compat\\.objectstorage\\.[a-z0-9-]+\\.",
            Pattern.CASE_INSENSITIVE);

    /** Whether {@code host} is an S3 Compatibility API host, the only place SigV4 credentials count. */
    static boolean isS3CompatHost(String host) {
        return host != null && S3_COMPAT_HOST.matcher(host).find();
    }

    /** {@code Credential=<accessKey>/<date>/<region>/s3/aws4_request} in a SigV4 Authorization header. */
    Pattern SIGV4_CREDENTIAL = Pattern.compile("^AWS4-HMAC-SHA256\\s+.*?Credential=([^/,\\s]+)/");

    /** The access key of a SigV4 {@code Authorization} header, or empty for any other scheme. */
    static Optional<String> sigV4AccessKey(String authorization) {
        if (authorization == null) {
            return Optional.empty();
        }
        Matcher m = SIGV4_CREDENTIAL.matcher(authorization);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** The access key of a presigned URL's {@code X-Amz-Credential} query value. */
    static Optional<String> presignedAccessKey(String credential) {
        if (credential == null || credential.isBlank()) {
            return Optional.empty();
        }
        int slash = credential.indexOf('/');
        return Optional.of(slash < 0 ? credential : credential.substring(0, slash));
    }
}
