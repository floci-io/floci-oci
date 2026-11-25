package io.floci.oci.core.common;

import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Checks the VCN, subnet and network security group OCIDs other services take in a request body
 * (OKE, Functions, PostgreSQL), so they do not depend on the Networking service. An id that does
 * not resolve in the caller's tenancy and region is a 400 RelatedResourceNotAuthorizedOrNotFound,
 * the body-reference error of Oracle's global catalog. Null and empty arguments are not checked:
 * whether a field is required is the caller's rule.
 */
public interface NetworkReferences {

    /** Accepts every id: what a service uses when Networking is not deployed. */
    NetworkReferences NONE = new NetworkReferences() {
        @Override
        public void requireVcn(String vcnId) {
        }

        @Override
        public void requireSubnets(Collection<String> subnetIds) {
        }

        @Override
        public void requireNsgs(Collection<String> nsgIds) {
        }
    };

    void requireVcn(String vcnId);

    void requireSubnets(Collection<String> subnetIds);

    void requireNsgs(Collection<String> nsgIds);

    default void requireSubnet(String subnetId) {
        if (subnetId != null) {
            requireSubnets(List.of(subnetId));
        }
    }

    /**
     * Like {@link #requireSubnets}, and each subnet must also be in {@code vcnId}: a subnet from
     * another VCN is a 400 InvalidParameter naming {@code field}. An implementation that cannot
     * see VCN membership only checks that the subnets exist.
     */
    default void requireSubnetsInVcn(Collection<String> subnetIds, String vcnId, String field) {
        requireSubnets(subnetIds);
    }

    /** The resolvable implementation, or {@link #NONE}. */
    static NetworkReferences of(Instance<NetworkReferences> instance) {
        return instance != null && instance.isResolvable() ? instance.get() : NONE;
    }

    /** The string ids of a JSON list field, or an empty list when it is absent or not a list. */
    static List<String> ids(Object value) {
        List<String> ids = new ArrayList<>();
        if (value instanceof Collection<?> items) {
            for (Object item : items) {
                if (item != null) {
                    ids.add(String.valueOf(item));
                }
            }
        }
        return ids;
    }
}
