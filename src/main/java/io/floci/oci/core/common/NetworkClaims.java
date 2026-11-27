package io.floci.oci.core.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The VCN, subnet and network security group OCIDs that creates and updates are using but have
 * not stored yet. {@link NetworkUsage} only sees saved resources, so without a claim a delete
 * could run between a create's reference check and its save (an OKE cluster starts its sidecar in
 * between) and leave the saved resource pointing at a network that is gone.
 *
 * <p>A service claims the ids before it checks them through {@link NetworkReferences} and closes
 * the claim once the resource is stored or the request failed. Networking refuses to delete a
 * claimed id. A claim is not a lock: nothing waits on it, so it may be held across a slow step.
 */
public final class NetworkClaims {

    private static final Map<String, Integer> CLAIMED = new ConcurrentHashMap<>();

    private NetworkClaims() {
    }

    /** Claims every id in {@code ids} until the returned claim is closed. */
    public static Claim claim(Collection<String> ids) {
        List<String> claimed = new ArrayList<>(ids);
        for (String id : claimed) {
            CLAIMED.merge(id, 1, Integer::sum);
        }
        return new Claim(claimed);
    }

    /** How many open claims hold {@code id}. */
    public static int pending(String id) {
        return CLAIMED.getOrDefault(id, 0);
    }

    /** Released once; closing it again does nothing. */
    public static final class Claim implements AutoCloseable {
        private List<String> ids;

        private Claim(List<String> ids) {
            this.ids = ids;
        }

        @Override
        public synchronized void close() {
            if (ids == null) {
                return;
            }
            for (String id : ids) {
                CLAIMED.computeIfPresent(id, (key, count) -> count > 1 ? count - 1 : null);
            }
            ids = null;
        }
    }
}
