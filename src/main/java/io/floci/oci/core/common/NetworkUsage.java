package io.floci.oci.core.common;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Implemented by every service that keeps VCN, subnet or network security group OCIDs on its
 * resources, so Networking can refuse to delete one that is still in use without depending on the
 * services. It is the network counterpart of {@link CompartmentUsage}: OCI answers
 * {@code 409 Conflict} to a DeleteSubnet while the subnet still has resources in it, and the
 * Terraform provider retries a subnet delete on that error while the dependants are torn down.
 */
public interface NetworkUsage {

    /** Lifecycle states of a resource that no longer holds its network references. */
    Set<String> GONE = Set.of("DELETED", "TERMINATED");

    /**
     * Short descriptions (e.g. {@code cluster ocid1.cluster...}) of this service's resources in the
     * caller's tenancy and region that still reference {@code networkId}, a VCN, subnet or network
     * security group OCID. Deleted resources do not count.
     */
    List<String> resourcesUsing(String networkId);

    /**
     * The {@code resources} whose {@code referencesOf} contains {@code networkId} and
     * whose lifecycle state is not one of {@link #GONE}.
     */
    static <V> Stream<String> using(Collection<V> resources, String networkId, Function<V, String> stateOf,
                                    Function<V, Collection<String>> referencesOf, Function<V, String> describe) {
        return resources.stream()
                .filter(resource -> !GONE.contains(stateOf.apply(resource)))
                .filter(resource -> referencesOf.apply(resource).contains(networkId))
                .map(describe);
    }

    /**
     * The OCIDs held in {@code values}: strings as they are, collections by their string items,
     * and nulls or anything else skipped. Lets a service list the fields that can hold a network
     * reference without checking each one.
     */
    static Set<String> ids(Object... values) {
        Set<String> ids = new LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof String id) {
                ids.add(id);
            } else if (value instanceof Collection<?> items) {
                for (Object item : items) {
                    if (item instanceof String id) {
                        ids.add(id);
                    }
                }
            }
        }
        return ids;
    }

    /** The value of {@code key} in {@code map}, or null when the map itself is null. */
    static Object field(Map<String, Object> map, String key) {
        return map == null ? null : map.get(key);
    }
}
