package io.floci.oci.services.networking;

import java.util.Arrays;
import java.util.Optional;

/**
 * The Core Virtual Network resource types floci-oci serves, each with its URI collection under
 * {@code /20160918} and its OCID resource type, which is also the default display-name prefix
 * (oci-go-sdk/core/core_virtualnetwork_client.go).
 */
public enum NetworkKind {
    VCN("vcns", "vcn"),
    SUBNET("subnets", "subnet"),
    INTERNET_GATEWAY("internetGateways", "internetgateway"),
    NAT_GATEWAY("natGateways", "natgateway"),
    SERVICE_GATEWAY("serviceGateways", "servicegateway"),
    ROUTE_TABLE("routeTables", "routetable"),
    SECURITY_LIST("securityLists", "securitylist"),
    NETWORK_SECURITY_GROUP("networkSecurityGroups", "networksecuritygroup"),
    DHCP_OPTIONS("dhcps", "dhcpoptions"),
    /** Not in {@link #COLLECTIONS}: VNICs are created by the services that attach them, never by CRUD. */
    VNIC("vnics", "vnic");

    /** The JAX-RS path regex matching every collection, for one generic CRUD resource. */
    public static final String COLLECTIONS =
            "vcns|subnets|internetGateways|natGateways|serviceGateways|routeTables|securityLists"
                    + "|networkSecurityGroups|dhcps";

    private final String collection;
    private final String ocidType;

    NetworkKind(String collection, String ocidType) {
        this.collection = collection;
        this.ocidType = ocidType;
    }

    public String collection() {
        return collection;
    }

    public String ocidType() {
        return ocidType;
    }

    /** VCN and subnet compartment moves are asynchronous and return {@code opc-work-request-id}. */
    public boolean compartmentMoveIsWorkRequest() {
        return this == VCN || this == SUBNET;
    }

    public static Optional<NetworkKind> byCollection(String collection) {
        return Arrays.stream(values()).filter(k -> k.collection.equals(collection)).findFirst();
    }
}
