package io.floci.oci.core.common;

import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Implemented by every service that keeps resources in compartments, so Identity can refuse to
 * delete a compartment that is not empty without knowing other services' stores. OCI requires a
 * compartment to be "empty of all resources" before it is deleted, in every region.
 */
public interface CompartmentUsage {

    /**
     * Short descriptions (e.g. {@code queue ocid1.queue...}) of the resources this service still
     * holds in {@code compartmentId}, in every region of {@code tenancyId}.
     */
    List<String> resourcesIn(String tenancyId, String compartmentId);

    /**
     * The entries of {@code store} in {@code compartmentId}, across every region of the tenancy,
     * whose lifecycle state is not {@code DELETED}. {@code stateOf} is null for resources without
     * a lifecycle (buckets).
     */
    static <V> Stream<String> live(StorageBackend<String, V> store, String tenancyId,
                                   String compartmentId, Function<V, String> compartmentOf,
                                   Function<V, String> stateOf, Function<V, String> describe) {
        return TenancyAwareStorageBackend.scanTenancy(store, tenancyId).stream()
                .filter(v -> compartmentId.equals(compartmentOf.apply(v)))
                .filter(v -> stateOf == null || !"DELETED".equals(stateOf.apply(v)))
                .map(describe);
    }
}
