package io.floci.oci.services.networking;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.NetworkUsage;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.networking.model.StoredNetworkResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class NetworkingServiceTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..networkingtest";
    private static final String OTHER_COMPARTMENT = "ocid1.compartment.oc1..networkingother";

    private NetworkingService service;
    private WorkRequestService workRequests;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.effectiveBaseUrl()).thenReturn("http://localhost:4599");
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        service = new NetworkingService(new InMemoryStorage<>(), config, workRequests);
    }

    private StoredNetworkResource vcn(String cidr, String dnsLabel) {
        Map<String, Object> body = new HashMap<>();
        body.put("compartmentId", COMPARTMENT);
        body.put("cidrBlocks", List.of(cidr));
        body.put("displayName", "main");
        if (dnsLabel != null) {
            body.put("dnsLabel", dnsLabel);
        }
        return service.create(NetworkKind.VCN, body);
    }

    private StoredNetworkResource subnet(StoredNetworkResource vcn, String cidr) {
        return service.create(NetworkKind.SUBNET, Map.of(
                "compartmentId", COMPARTMENT, "vcnId", vcn.getId(), "cidrBlock", cidr));
    }

    @Test
    void createVcnMakesTheThreeDefaults() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", "main");

        assertTrue(vcn.getId().startsWith("ocid1.vcn.oc1.iad."));
        assertEquals("AVAILABLE", vcn.getLifecycleState());
        assertEquals("10.0.0.0/16", vcn.getAttributes().get("cidrBlock"));
        assertEquals("main.oraclevcn.com", vcn.getAttributes().get("vcnDomainName"));

        StoredNetworkResource routeTable = service.get(NetworkKind.ROUTE_TABLE,
                (String) vcn.getAttributes().get("defaultRouteTableId"));
        assertEquals("Default Route Table for main", routeTable.getDisplayName());
        assertEquals(List.of(), routeTable.getAttributes().get("routeRules"));

        StoredNetworkResource securityList = service.get(NetworkKind.SECURITY_LIST,
                (String) vcn.getAttributes().get("defaultSecurityListId"));
        assertEquals("Default Security List for main", securityList.getDisplayName());
        assertEquals(3, ((List<?>) securityList.getAttributes().get("ingressSecurityRules")).size());

        StoredNetworkResource dhcp = service.get(NetworkKind.DHCP_OPTIONS,
                (String) vcn.getAttributes().get("defaultDhcpOptionsId"));
        assertEquals("Default DHCP Options for main", dhcp.getDisplayName());
        assertEquals(2, ((List<?>) dhcp.getAttributes().get("options")).size());
    }

    @Test
    void vcnWithoutDnsLabelHasOnlyTheDnsServerOption() {
        StoredNetworkResource vcn = vcn("10.1.0.0/16", null);
        StoredNetworkResource dhcp = service.get(NetworkKind.DHCP_OPTIONS,
                (String) vcn.getAttributes().get("defaultDhcpOptionsId"));
        assertEquals(1, ((List<?>) dhcp.getAttributes().get("options")).size());
        assertFalse(vcn.getAttributes().containsKey("vcnDomainName"));
    }

    @Test
    void vcnDefaultNameUsesTheTimestampPattern() {
        StoredNetworkResource vcn = service.create(NetworkKind.VCN,
                Map.of("compartmentId", COMPARTMENT, "cidrBlock", "10.2.0.0/16"));
        assertTrue(vcn.getDisplayName().matches("vcn\\d{14}"), vcn.getDisplayName());
    }

    @Test
    void vcnCidrOutsideSixteenToThirtyIsRejected() {
        OciException e = assertThrows(OciException.class, () -> vcn("10.0.0.0/8", null));
        assertEquals(400, e.getHttpStatus());
        assertEquals("InvalidParameter", e.getCode());
    }

    @Test
    void subnetDefaultsToTheVcnDefaultsAndDerivesRouterIp() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", "main");
        StoredNetworkResource subnet = service.create(NetworkKind.SUBNET, Map.of(
                "compartmentId", COMPARTMENT, "vcnId", vcn.getId(), "cidrBlock", "10.0.1.0/24",
                "dnsLabel", "app"));

        Map<String, Object> a = subnet.getAttributes();
        assertEquals(vcn.getAttributes().get("defaultRouteTableId"), a.get("routeTableId"));
        assertEquals(vcn.getAttributes().get("defaultDhcpOptionsId"), a.get("dhcpOptionsId"));
        assertEquals(List.of(vcn.getAttributes().get("defaultSecurityListId")), a.get("securityListIds"));
        assertEquals("10.0.1.1", a.get("virtualRouterIp"));
        assertEquals("app.main.oraclevcn.com", a.get("subnetDomainName"));
        assertEquals(false, a.get("prohibitInternetIngress"));
        assertEquals(false, a.get("prohibitPublicIpOnVnic"));
    }

    @Test
    void subnetOutsideTheVcnIsRejected() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", null);
        OciException e = assertThrows(OciException.class, () -> subnet(vcn, "192.168.0.0/24"));
        assertEquals("InvalidParameter", e.getCode());
    }

    @Test
    void overlappingSubnetsAreRejected() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", null);
        subnet(vcn, "10.0.0.0/24");
        OciException e = assertThrows(OciException.class, () -> subnet(vcn, "10.0.0.128/25"));
        assertEquals("InvalidParameter", e.getCode());
    }

    @Test
    void subnetInAnUnknownVcnIsARelatedResourceError() {
        OciException e = assertThrows(OciException.class, () -> service.create(NetworkKind.SUBNET, Map.of(
                "compartmentId", COMPARTMENT, "vcnId", "ocid1.vcn.oc1.iad.missing", "cidrBlock", "10.0.0.0/24")));
        assertEquals(400, e.getHttpStatus());
        assertEquals("RelatedResourceNotAuthorizedOrNotFound", e.getCode());
    }

    @Test
    void deletingAVcnWithASubnetConflictsAndDefaultsGoWithTheVcn() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", null);
        StoredNetworkResource subnet = subnet(vcn, "10.0.0.0/24");

        OciException e = assertThrows(OciException.class, () -> service.delete(NetworkKind.VCN, vcn.getId(), null));
        assertEquals(409, e.getHttpStatus());
        assertEquals("Conflict", e.getCode());

        service.delete(NetworkKind.SUBNET, subnet.getId(), null);
        service.delete(NetworkKind.VCN, vcn.getId(), null);
        assertTrue(service.list(NetworkKind.ROUTE_TABLE, COMPARTMENT, null, null, null, null, null).isEmpty());
        assertThrows(OciException.class, () -> service.get(NetworkKind.VCN, vcn.getId()));
    }

    @Test
    void defaultRouteTableCannotBeDeleted() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", null);
        OciException e = assertThrows(OciException.class, () -> service.delete(NetworkKind.ROUTE_TABLE,
                (String) vcn.getAttributes().get("defaultRouteTableId"), null));
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void routeRuleToAGatewayBlocksTheGatewayDelete() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", null);
        StoredNetworkResource igw = service.create(NetworkKind.INTERNET_GATEWAY, Map.of(
                "compartmentId", COMPARTMENT, "vcnId", vcn.getId(), "isEnabled", true));
        StoredNetworkResource rt = service.create(NetworkKind.ROUTE_TABLE, Map.of(
                "compartmentId", COMPARTMENT, "vcnId", vcn.getId(),
                "routeRules", List.of(Map.of("destination", "0.0.0.0/0", "networkEntityId", igw.getId()))));

        @SuppressWarnings("unchecked")
        Map<String, Object> rule = ((List<Map<String, Object>>) rt.getAttributes().get("routeRules")).get(0);
        assertEquals("0.0.0.0/0", rule.get("cidrBlock"));
        assertEquals("CIDR_BLOCK", rule.get("destinationType"));
        assertEquals("STATIC", rule.get("routeType"));

        OciException e = assertThrows(OciException.class,
                () -> service.delete(NetworkKind.INTERNET_GATEWAY, igw.getId(), null));
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void routeRuleWithDifferentCidrBlockAndDestinationIsRejected() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", null);
        StoredNetworkResource igw = service.create(NetworkKind.INTERNET_GATEWAY, Map.of(
                "compartmentId", COMPARTMENT, "vcnId", vcn.getId(), "isEnabled", true));
        assertThrows(OciException.class, () -> service.create(NetworkKind.ROUTE_TABLE, Map.of(
                "compartmentId", COMPARTMENT, "vcnId", vcn.getId(),
                "routeRules", List.of(Map.of("cidrBlock", "10.9.0.0/16", "destination", "0.0.0.0/0",
                        "networkEntityId", igw.getId())))));
    }

    @Test
    void serviceGatewayResolvesServiceNames() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", null);
        Map<String, Object> all = service.listServices().get(0);
        StoredNetworkResource sgw = service.create(NetworkKind.SERVICE_GATEWAY, Map.of(
                "compartmentId", COMPARTMENT, "vcnId", vcn.getId(),
                "services", List.of(Map.of("serviceId", all.get("id")))));

        @SuppressWarnings("unchecked")
        Map<String, Object> ref = ((List<Map<String, Object>>) sgw.getAttributes().get("services")).get(0);
        assertEquals("All IAD Services In Oracle Services Network", ref.get("serviceName"));
        assertEquals("all-iad-services-in-oracle-services-network", all.get("cidrBlock"));
    }

    @Test
    void serviceIdsAreStableAcrossInstances() {
        assertEquals(service.listServices(), service.listServices());
        assertTrue(((String) service.listServices().get(1).get("id")).startsWith("ocid1.service.oc1.iad."));
    }

    @Test
    void nsgRulesAddUpdateRemove() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", null);
        StoredNetworkResource nsg = service.create(NetworkKind.NETWORK_SECURITY_GROUP, Map.of(
                "compartmentId", COMPARTMENT, "vcnId", vcn.getId()));

        List<Map<String, Object>> added = service.addSecurityRules(nsg.getId(), List.of(Map.of(
                "direction", "INGRESS", "protocol", "6", "source", "10.0.0.0/16")));
        String ruleId = (String) added.get(0).get("id");
        assertTrue(ruleId.matches("[0-9A-F]{6}"), ruleId);
        assertEquals("CIDR_BLOCK", added.get(0).get("sourceType"));
        assertEquals(true, added.get(0).get("isValid"));

        service.updateSecurityRules(nsg.getId(), List.of(Map.of(
                "id", ruleId, "direction", "INGRESS", "protocol", "17", "source", "10.0.0.0/16")));
        assertEquals("17", service.listSecurityRules(nsg.getId(), "INGRESS").get(0).get("protocol"));
        assertTrue(service.listSecurityRules(nsg.getId(), "EGRESS").isEmpty());

        service.removeSecurityRules(nsg.getId(), List.of(ruleId));
        assertTrue(service.listSecurityRules(nsg.getId(), null).isEmpty());
    }

    @Test
    void rejectedUpdateLeavesTheStoredResourceUnchanged() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", null);
        StoredNetworkResource igw = service.create(NetworkKind.INTERNET_GATEWAY, Map.of(
                "compartmentId", COMPARTMENT, "vcnId", vcn.getId(), "isEnabled", true, "displayName", "igw"));
        String etag = igw.getEtag();

        assertThrows(OciException.class, () -> service.update(NetworkKind.INTERNET_GATEWAY, igw.getId(), Map.of(
                "displayName", "renamed", "isEnabled", false, "routeTableId", "ocid1.routetable.oc1.iad.missing"),
                null));

        StoredNetworkResource stored = service.get(NetworkKind.INTERNET_GATEWAY, igw.getId());
        assertEquals("igw", stored.getDisplayName());
        assertEquals(true, stored.getAttributes().get("isEnabled"));
        assertEquals(etag, stored.getEtag());
    }

    @Test
    void rulesAddedInOneBatchGetDistinctIds() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", null);
        StoredNetworkResource nsg = service.create(NetworkKind.NETWORK_SECURITY_GROUP, Map.of(
                "compartmentId", COMPARTMENT, "vcnId", vcn.getId()));
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            batch.add(Map.of("direction", "INGRESS", "protocol", "6", "source", "10.0.0.0/16"));
        }

        List<Map<String, Object>> added = service.addSecurityRules(nsg.getId(), batch);
        assertEquals(3000, added.stream().map(rule -> rule.get("id")).distinct().count());
    }

    @Test
    void rejectedRuleBatchLeavesEveryRuleUnchanged() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", null);
        StoredNetworkResource nsg = service.create(NetworkKind.NETWORK_SECURITY_GROUP, Map.of(
                "compartmentId", COMPARTMENT, "vcnId", vcn.getId()));
        String ruleId = (String) service.addSecurityRules(nsg.getId(), List.of(Map.of(
                "direction", "INGRESS", "protocol", "6", "source", "10.0.0.0/16"))).get(0).get("id");

        assertThrows(OciException.class, () -> service.updateSecurityRules(nsg.getId(), List.of(
                Map.of("id", ruleId, "direction", "INGRESS", "protocol", "17", "source", "10.0.0.0/16"),
                Map.of("id", "FFFFFF", "direction", "INGRESS", "protocol", "17", "source", "10.0.0.0/16"))));
        assertEquals("6", service.listSecurityRules(nsg.getId(), null).get(0).get("protocol"));

        assertThrows(OciException.class, () -> service.removeSecurityRules(nsg.getId(), List.of(ruleId, "FFFFFF")));
        assertEquals(1, service.listSecurityRules(nsg.getId(), null).size());
    }

    @Test
    void vcnCompartmentMoveRecordsANetworkingWorkRequest() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", null);
        NetworkingService.CompartmentMove move =
                service.changeCompartment(NetworkKind.VCN, vcn.getId(), OTHER_COMPARTMENT, null);

        assertEquals(OTHER_COMPARTMENT, service.get(NetworkKind.VCN, vcn.getId()).getCompartmentId());
        StoredWorkRequest wr = workRequests.get("networking", move.workRequestId());
        assertEquals("SUCCEEDED", wr.getStatus());
        assertEquals(vcn.getId(), wr.getResources().get(0).getIdentifier());
    }

    @Test
    void routeTableCompartmentMoveHasNoWorkRequest() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", null);
        NetworkingService.CompartmentMove move = service.changeCompartment(NetworkKind.ROUTE_TABLE,
                (String) vcn.getAttributes().get("defaultRouteTableId"), OTHER_COMPARTMENT, null);
        assertNull(move.workRequestId());
    }

    @Test
    void updateWithStaleEtagFails() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", null);
        OciException e = assertThrows(OciException.class,
                () -> service.update(NetworkKind.VCN, vcn.getId(), Map.of("displayName", "x"), "stale"));
        assertEquals(412, e.getHttpStatus());
    }

    @Test
    void listSortsByTimeCreatedDescendingByDefault() throws InterruptedException {
        StoredNetworkResource first = vcn("10.0.0.0/16", null);
        Thread.sleep(5);
        StoredNetworkResource second = vcn("10.1.0.0/16", null);
        List<StoredNetworkResource> listed =
                service.list(NetworkKind.VCN, COMPARTMENT, null, null, null, null, null);
        assertEquals(List.of(second.getId(), first.getId()),
                listed.stream().map(StoredNetworkResource::getId).toList());
    }

    @Test
    void customDnsServerRequiresServers() {
        StoredNetworkResource vcn = vcn("10.0.0.0/16", null);
        assertThrows(OciException.class, () -> service.create(NetworkKind.DHCP_OPTIONS, Map.of(
                "compartmentId", COMPARTMENT, "vcnId", vcn.getId(),
                "options", List.of(Map.of("type", "DomainNameServer", "serverType", "CustomDnsServer")))));
    }

    @Test
    void resourcesInCountsTheVcnAndItsDefaults() {
        vcn("10.0.0.0/16", null);
        assertEquals(4, service.resourcesIn("ocid1.tenancy.oc1..test", COMPARTMENT).size());
    }

    @Test
    void vcnListenersHearCreateAndDeleteAndAnswerTheResolverAssociation() {
        List<String> events = new ArrayList<>();
        VcnLifecycleListener listener = new VcnLifecycleListener() {
            @Override
            public void vcnCreated(Vcn vcn) {
                events.add("created " + vcn.displayName() + " in " + vcn.compartmentId());
            }

            @Override
            public void vcnDeleted(String vcnId) {
                events.add("deleted " + vcnId);
            }

            @Override
            public Optional<String> dnsResolverId(Vcn vcn) {
                return Optional.of("resolver-of-" + vcn.id());
            }
        };
        VcnLifecycleListener failing = new VcnLifecycleListener() {
            @Override
            public void vcnCreated(Vcn vcn) {
                throw new IllegalStateException("listener failure");
            }

            @Override
            public void vcnDeleted(String vcnId) {
                throw new IllegalStateException("listener failure");
            }
        };
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        NetworkingService withListeners = new NetworkingService(new InMemoryStorage<>(), config, workRequests,
                List.of(failing, listener));

        StoredNetworkResource vcn = withListeners.create(NetworkKind.VCN,
                Map.of("compartmentId", COMPARTMENT, "cidrBlock", "10.9.0.0/16", "displayName", "heard"));
        assertEquals(Optional.of("resolver-of-" + vcn.getId()), withListeners.dnsResolverId(vcn.getId()));
        withListeners.delete(NetworkKind.VCN, vcn.getId(), null);

        assertEquals(List.of("created heard in " + COMPARTMENT, "deleted " + vcn.getId()), events);
        assertEquals(Optional.empty(), service.dnsResolverId(vcn("10.8.0.0/16", null).getId()));
    }

    @Test
    void aVcnSubnetOrNsgAnotherServiceStillUsesCannotBeDeleted() {
        Map<String, List<String>> users = new HashMap<>();
        NetworkUsage usage = networkId -> users.getOrDefault(networkId, List.of());
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        NetworkingService guarded = new NetworkingService(new InMemoryStorage<>(), config, workRequests,
                List.of(), List.of(usage));
        StoredNetworkResource vcn = guarded.create(NetworkKind.VCN,
                Map.of("compartmentId", COMPARTMENT, "cidrBlock", "10.7.0.0/16", "displayName", "used"));
        StoredNetworkResource subnet = guarded.create(NetworkKind.SUBNET,
                Map.of("compartmentId", COMPARTMENT, "vcnId", vcn.getId(), "cidrBlock", "10.7.1.0/24"));
        StoredNetworkResource nsg = guarded.create(NetworkKind.NETWORK_SECURITY_GROUP,
                Map.of("compartmentId", COMPARTMENT, "vcnId", vcn.getId()));
        users.put(subnet.getId(), List.of("application ocid1.fnapp.oc1.iad.a"));
        users.put(nsg.getId(), List.of("DB system ocid1.postgresqldbsystem.oc1.iad.b"));
        users.put(vcn.getId(), List.of("cluster ocid1.cluster.oc1.iad.c"));

        OciException subnetRefused = assertThrows(OciException.class,
                () -> guarded.delete(NetworkKind.SUBNET, subnet.getId(), null));
        assertEquals(409, subnetRefused.getHttpStatus());
        assertEquals("Conflict", subnetRefused.getCode());
        assertEquals("The subnet " + subnet.getId() + " is still used by other resources and cannot be deleted: "
                + "application ocid1.fnapp.oc1.iad.a", subnetRefused.getMessage());
        assertEquals("Conflict", assertThrows(OciException.class,
                () -> guarded.delete(NetworkKind.NETWORK_SECURITY_GROUP, nsg.getId(), null)).getCode());

        users.clear();
        users.put(vcn.getId(), List.of("cluster ocid1.cluster.oc1.iad.c"));
        guarded.delete(NetworkKind.SUBNET, subnet.getId(), null);
        guarded.delete(NetworkKind.NETWORK_SECURITY_GROUP, nsg.getId(), null);
        assertTrue(assertThrows(OciException.class, () -> guarded.delete(NetworkKind.VCN, vcn.getId(), null))
                .getMessage().contains("cluster ocid1.cluster.oc1.iad.c"));

        users.clear();
        guarded.delete(NetworkKind.VCN, vcn.getId(), null);
        assertThrows(OciException.class, () -> guarded.get(NetworkKind.VCN, vcn.getId()));
    }
}
