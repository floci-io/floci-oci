package io.floci.oci.core.common;

import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Checks the VCN, subnet and network security group OCIDs other services take in a request body
 * (OKE, Functions, PostgreSQL), so they do not depend on the Networking service. An id that does
 * not resolve in the caller's tenancy and region is a 400 RelatedResourceNotAuthorizedOrNotFound,
 * the body-reference error of Oracle's global catalog. Null and empty arguments are not checked:
 * whether a field is required is the caller's rule.
 *
 * <p>It also attaches VNICs for services that place something on a subnet (Container Instances),
 * so Core GetVnic can return them.
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

        @Override
        public String attachVnic(VnicRequest request) {
            return Ocids.generateGlobal("vnic", "oc1");
        }

        @Override
        public void setVnicPrivateIp(String vnicId, String privateIp) {
        }

        @Override
        public void detachVnic(String vnicId) {
        }
    };

    /**
     * A VNIC to create on a subnet (CreateContainerVnicDetails in oci-go-sdk/containerinstances).
     *
     * @param privateIp the requested address, or null for the next free one in the subnet
     * @param allocatePrivateIp false to leave the address unset until {@link #setVnicPrivateIp}
     *                          records the one the workload really got
     */
    record VnicRequest(String compartmentId, String availabilityDomain, String subnetId, String displayName,
                       String hostnameLabel, List<String> nsgIds, Boolean skipSourceDestCheck, String privateIp,
                       boolean allocatePrivateIp, boolean publicIpAssigned, Map<String, String> freeformTags,
                       Map<String, Map<String, Object>> definedTags) {
    }

    void requireVcn(String vcnId);

    void requireSubnets(Collection<String> subnetIds);

    void requireNsgs(Collection<String> nsgIds);

    /**
     * Creates a VNIC on {@code request.subnetId()} and returns its OCID. Checks the subnet and the
     * NSGs like {@link #requireSubnets} and {@link #requireNsgs}.
     */
    String attachVnic(VnicRequest request);

    /** Records the address a VNIC created without one really got; a VNIC that has one keeps it. */
    void setVnicPrivateIp(String vnicId, String privateIp);

    /** Deletes a VNIC; one that is already gone is ignored. */
    void detachVnic(String vnicId);

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

    /**
     * The string ids of the JSON list field {@code field}, or an empty list when it is absent. A
     * value that is not a list of strings is a 400 InvalidParameter, so a malformed reference is
     * never stored unchecked.
     */
    static List<String> ids(Object value, String field) {
        List<String> ids = new ArrayList<>();
        if (value == null) {
            return ids;
        }
        if (!(value instanceof Collection<?> items)) {
            throw OciException.invalidParameter(field + " must be a list of OCIDs");
        }
        for (Object item : items) {
            if (!(item instanceof String id)) {
                throw OciException.invalidParameter(field + " must be a list of OCIDs");
            }
            ids.add(id);
        }
        return ids;
    }
}
