package io.floci.oci.core.auth;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Checks a password against the active Identity auth tokens of a user, for the protocols that log in
 * with one instead of signing: OCIR {@code docker login} (Registry/Tasks/registrypushingimagesusingthedockercli.htm)
 * and the Streaming Kafka SASL/PLAIN endpoint. Users are named by name there, not by OCID, and the
 * tenancy by something only the caller can map (the Object Storage namespace, for OCIR), so the caller
 * says which tenancies qualify.
 */
public interface AuthTokenResolver {

    /** The tenancy and user whose active auth token {@code token} is, among the tenancies {@code inTenancy} accepts. */
    Optional<AuthContext> resolve(Predicate<String> inTenancy, String userName, String token);
}
