package io.floci.oci.services.networking;

import java.util.Optional;

/**
 * Told when a VCN is created or deleted, so another service can keep what it attaches to each VCN:
 * DNS keeps a VCN-dedicated resolver with a protected default view. Called inside the request, in
 * the VCN's tenancy and region; a listener that fails is logged and does not fail the request.
 */
public interface VcnLifecycleListener {

    record Vcn(String id, String compartmentId, String displayName) {
    }

    void vcnCreated(Vcn vcn);

    void vcnDeleted(String vcnId);

    /** The OCID of the DNS resolver attached to {@code vcn}, created first if missing; empty when none applies. */
    default Optional<String> dnsResolverId(Vcn vcn) {
        return Optional.empty();
    }
}
