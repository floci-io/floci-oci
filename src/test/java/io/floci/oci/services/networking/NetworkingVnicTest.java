package io.floci.oci.services.networking;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.NetworkReferences;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.networking.model.StoredNetworkResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkingVnicTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..vnictest";

    private EmulatorConfig config;
    private NetworkingService service;
    private String subnetId;
    private String nsgId;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.services().networking().enabled()).thenReturn(true);
        service = new NetworkingService(new InMemoryStorage<>(), config,
                new WorkRequestService(new InMemoryStorage<>(), config));
        String vcnId = service.create(NetworkKind.VCN, Map.of("compartmentId", COMPARTMENT,
                "cidrBlocks", List.of("10.0.0.0/16"))).getId();
        subnetId = service.create(NetworkKind.SUBNET, Map.of("compartmentId", COMPARTMENT, "vcnId", vcnId,
                "cidrBlock", "10.0.0.0/30")).getId();
        nsgId = service.create(NetworkKind.NETWORK_SECURITY_GROUP, Map.of("compartmentId", COMPARTMENT,
                "vcnId", vcnId)).getId();
    }

    private String attach(String privateIp, boolean allocate) {
        return service.attachVnic(new NetworkReferences.VnicRequest(COMPARTMENT, "Floc:US-ASHBURN-AD-1", subnetId,
                null, "web", List.of(nsgId), null, privateIp, allocate, false, null, null));
    }

    private Object privateIp(String vnicId) {
        return service.get(NetworkKind.VNIC, vnicId).getAttributes().get("privateIp");
    }

    @Test
    void addressesSkipTheNetworkTheRouterAndTheBroadcast() {
        // A /30 has four addresses: .0 network, .1 virtual router, .3 broadcast; only .2 is free.
        assertEquals("10.0.0.2", privateIp(attach(null, true)));
        OciException full = assertThrows(OciException.class, () -> attach(null, true));
        assertEquals("InvalidParameter", full.getCode());
    }

    @Test
    void aRequestedAddressMustBeInTheSubnetAndFree() {
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> attach("10.0.9.9", true)).getCode());
        assertEquals("10.0.0.2", privateIp(attach("10.0.0.2", true)));
        assertEquals("Conflict", assertThrows(OciException.class, () -> attach("10.0.0.2", true)).getCode());
    }

    @Test
    void aWorkloadAddressIsRecordedLater() {
        String vnicId = attach(null, false);
        assertNull(privateIp(vnicId));
        service.setVnicPrivateIp(vnicId, "172.18.0.4");
        assertEquals("172.18.0.4", privateIp(vnicId));
    }

    @Test
    void theVnicKeepsWhatWasAskedForAndBlocksItsSubnet() {
        String vnicId = attach(null, true);
        StoredNetworkResource vnic = service.get(NetworkKind.VNIC, vnicId);
        assertTrue(vnicId.startsWith("ocid1.vnic.oc1.iad."));
        assertEquals(subnetId, vnic.getAttributes().get("subnetId"));
        assertEquals("web", vnic.getAttributes().get("hostnameLabel"));
        assertEquals(List.of(nsgId), vnic.getAttributes().get("nsgIds"));
        assertEquals(true, vnic.getAttributes().get("isPrimary"));
        assertFalse(vnic.getAttributes().containsKey("publicIp"));
        assertEquals(List.of(vnicId), service.vnicsInNsg(nsgId).stream().map(StoredNetworkResource::getId).toList());

        assertEquals("Conflict", assertThrows(OciException.class,
                () -> service.delete(NetworkKind.SUBNET, subnetId, null)).getCode());
        service.detachVnic(vnicId);
        service.detachVnic(vnicId);
        service.delete(NetworkKind.SUBNET, subnetId, null);
    }

    @Test
    void aNetworkSecurityGroupWithVnicsCannotBeDeleted() {
        String vnicId = attach(null, true);
        OciException e = assertThrows(OciException.class,
                () -> service.delete(NetworkKind.NETWORK_SECURITY_GROUP, nsgId, null));
        assertEquals("Conflict", e.getCode());
        assertEquals(409, e.getHttpStatus());
        assertEquals("Network security group " + nsgId + " still contains VNICs.", e.getMessage());

        service.detachVnic(vnicId);
        service.delete(NetworkKind.NETWORK_SECURITY_GROUP, nsgId, null);
        assertThrows(OciException.class, () -> service.get(NetworkKind.NETWORK_SECURITY_GROUP, nsgId));
    }

    @Test
    void anUnknownSubnetOrNsgIsRejected() {
        OciException e = assertThrows(OciException.class, () -> service.attachVnic(new NetworkReferences.VnicRequest(
                COMPARTMENT, null, "ocid1.subnet.oc1.iad.missing", null, null, null, null, null, true, false,
                null, null)));
        assertEquals("RelatedResourceNotAuthorizedOrNotFound", e.getCode());
        assertThrows(OciException.class, () -> service.attachVnic(new NetworkReferences.VnicRequest(
                COMPARTMENT, null, subnetId, null, null, List.of("ocid1.networksecuritygroup.oc1.iad.missing"),
                null, null, true, false, null, null)));
    }

    @Test
    void withNetworkingDisabledAVnicIsOnlyAnId() {
        when(config.services().networking().enabled()).thenReturn(false);
        String vnicId = attach(null, true);
        assertTrue(vnicId.startsWith("ocid1.vnic."));
        assertThrows(OciException.class, () -> service.get(NetworkKind.VNIC, vnicId));
    }
}
