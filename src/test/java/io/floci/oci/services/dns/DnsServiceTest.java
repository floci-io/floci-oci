package io.floci.oci.services.dns;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.services.dns.DnsService.RecordFilter;
import io.floci.oci.services.dns.DnsService.RecordScope;
import io.floci.oci.services.dns.DnsService.ZoneQuery;
import io.floci.oci.services.dns.model.StoredRecord;
import io.floci.oci.services.dns.model.StoredResolver;
import io.floci.oci.services.dns.model.StoredView;
import io.floci.oci.services.dns.model.StoredZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class DnsServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..dnstest";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..dnstest";

    private DnsService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        service = new DnsService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), config,
                null, null);
    }

    private StoredZone globalZone(String name) {
        return service.createZone(Map.of("name", name, "compartmentId", COMPARTMENT), null, null, null);
    }

    private static Map<String, Object> op(String operation, String domain, String rtype, String rdata, Integer ttl) {
        Map<String, Object> op = new HashMap<>();
        op.put("operation", operation);
        op.put("domain", domain);
        op.put("rtype", rtype);
        if (rdata != null) {
            op.put("rdata", rdata);
        }
        if (ttl != null) {
            op.put("ttl", ttl);
        }
        return op;
    }

    private List<StoredRecord> rrset(StoredZone zone, String domain, String rtype) {
        return service.records(service.getZone(zone.getId(), null), new RecordScope(domain, rtype), RecordFilter.NONE);
    }

    private static void assertError(int status, String code, Runnable call) {
        OciException e = assertThrows(OciException.class, call::run);
        assertEquals(status, e.getHttpStatus(), e.getMessage());
        assertEquals(code, e.getCode());
    }

    @Test
    void createZone_seedsProtectedSoaAndNs() {
        StoredZone zone = globalZone("example.com.");

        assertEquals("example.com", zone.getName());
        assertEquals("GLOBAL", zone.getScope());
        assertEquals("PRIMARY", zone.getZoneType());
        assertEquals("ACTIVE", zone.getLifecycleState());
        assertEquals(1, zone.getSerial());
        assertTrue(zone.getId().startsWith("ocid1.dns-zone.oc1.iad."));
        List<StoredRecord> soa = rrset(zone, "example.com", "SOA");
        assertEquals(1, soa.size());
        assertTrue(soa.getFirst().isProtectedRecord());
        assertEquals("ns1.dns.localhost.floci.io. hostmaster.example.com. 1 3600 600 604800 1800",
                soa.getFirst().getRdata());
        List<StoredRecord> ns = rrset(zone, "example.com", "NS");
        assertEquals(4, ns.size());
        assertTrue(ns.stream().allMatch(r -> r.isProtectedRecord() && r.getRdata().endsWith(".")));
    }

    @Test
    void getZone_byNameAndById() {
        StoredZone zone = globalZone("byname.example");

        assertEquals(zone.getId(), service.getZone("byname.example", null).getId());
        assertEquals(zone.getId(), service.getZone("BYNAME.example.", null).getId());
        assertEquals(zone.getId(), service.getZone(zone.getId(), null).getId());
        assertError(404, "NotAuthorizedOrNotFound", () -> service.getZone("missing.example", null));
    }

    @Test
    void createZone_validatesScopeAndView() {
        assertError(400, "MissingParameter", () -> service.createZone(
                Map.of("name", "p.example", "compartmentId", COMPARTMENT, "scope", "PRIVATE"), null, null, null));
        assertError(400, "RelatedResourceNotAuthorizedOrNotFound", () -> service.createZone(
                Map.of("name", "p.example", "compartmentId", COMPARTMENT, "scope", "PRIVATE",
                        "viewId", "ocid1.dnsview.oc1.iad.missing"), null, null, null));
        StoredView view = service.createView(Map.of("compartmentId", COMPARTMENT, "displayName", "v"));
        assertError(400, "InvalidParameter", () -> service.createZone(
                Map.of("name", "g.example", "compartmentId", COMPARTMENT, "viewId", view.getId()), null, null, null));
        assertError(501, "MethodNotImplemented", () -> service.createZone(
                Map.of("name", "s.example", "compartmentId", COMPARTMENT, "zoneType", "SECONDARY"), null, null, null));

        StoredZone zone = service.createZone(Map.of("name", "p.example", "compartmentId", COMPARTMENT,
                "scope", "PRIVATE", "viewId", view.getId()), null, null, null);
        assertEquals("PRIVATE", zone.getScope());
        assertEquals(view.getId(), zone.getViewId());
        assertError(400, "MissingParameter", () -> service.getZone("p.example", new ZoneQuery("PRIVATE", null, null)));
        assertEquals(zone.getId(), service.getZone("p.example", new ZoneQuery("PRIVATE", view.getId(), null)).getId());
        assertError(404, "NotAuthorizedOrNotFound", () -> service.getZone("p.example", null));
        assertError(409, "Conflict", () -> service.createZone(Map.of("name", "p.example",
                "compartmentId", COMPARTMENT, "scope", "PRIVATE", "viewId", view.getId()), null, null, null));
    }

    @Test
    void patch_addRemoveRequireProhibit() {
        StoredZone zone = globalZone("patch.example");

        service.patchRecords(zone.getId(), null, RecordScope.ZONE, List.of(
                op("PROHIBIT", "www.patch.example", "A", null, null),
                op("ADD", "www.patch.example", "A", "192.0.2.1", 300),
                op("ADD", "www.patch.example", "A", "192.0.2.2", 300)), null);
        assertEquals(2, rrset(zone, "www.patch.example", "A").size());

        service.patchRecords(zone.getId(), null, RecordScope.ZONE, List.of(
                op("REQUIRE", "www.patch.example", "A", "192.0.2.1", null),
                op("REMOVE", "www.patch.example", "A", "192.0.2.1", null)), null);
        List<StoredRecord> left = rrset(zone, "www.patch.example", "A");
        assertEquals(List.of("192.0.2.2"), left.stream().map(StoredRecord::getRdata).toList());

        service.patchRecords(zone.getId(), null, RecordScope.ZONE,
                List.of(op("ADD", "www.patch.example", "A", null, 60)), null);
        assertEquals(60, rrset(zone, "www.patch.example", "A").getFirst().getTtl());
    }

    @Test
    void patch_failedPreconditionIs412AndChangesNothing() {
        StoredZone zone = globalZone("atomic.example");
        long serial = zone.getSerial();

        assertError(412, "NoEtagMatch", () -> service.patchRecords(zone.getId(), null, RecordScope.ZONE, List.of(
                op("ADD", "a.atomic.example", "A", "192.0.2.1", 30),
                op("REQUIRE", "b.atomic.example", "A", null, null)), null));
        assertTrue(rrset(zone, "a.atomic.example", "A").isEmpty());
        assertEquals(serial, service.getZone(zone.getId(), null).getSerial());

        service.patchRecords(zone.getId(), null, RecordScope.ZONE,
                List.of(op("ADD", "a.atomic.example", "A", "192.0.2.1", 30)), null);
        assertError(412, "NoEtagMatch", () -> service.patchRecords(zone.getId(), null,
                new RecordScope("a.atomic.example", "A"),
                List.of(Map.of("operation", "PROHIBIT"), Map.of("operation", "ADD", "rdata", "192.0.2.9", "ttl", 30)),
                null));
        assertEquals(1, rrset(zone, "a.atomic.example", "A").size());
    }

    @Test
    void writes_bumpSerialAndChangedRrsetVersionsOnly() {
        StoredZone zone = globalZone("serial.example");
        service.patchRecords(zone.getId(), null, RecordScope.ZONE, List.of(
                op("ADD", "a.serial.example", "A", "192.0.2.1", 30),
                op("ADD", "b.serial.example", "A", "192.0.2.2", 30)), null);
        StoredZone after = service.getZone(zone.getId(), null);
        assertEquals(2, after.getSerial());

        service.patchRecords(zone.getId(), null, RecordScope.ZONE,
                List.of(op("ADD", "a.serial.example", "A", "192.0.2.3", 30)), null);
        StoredZone third = service.getZone(zone.getId(), null);
        assertEquals(3, third.getSerial());
        assertTrue(rrset(zone, "a.serial.example", "A").stream().allMatch(r -> "3".equals(r.getRrsetVersion())));
        assertEquals("2", rrset(zone, "b.serial.example", "A").getFirst().getRrsetVersion());
        StoredRecord soa = rrset(zone, "serial.example", "SOA").getFirst();
        assertEquals("3", soa.getRrsetVersion());
        assertTrue(soa.getRdata().contains(" 3 3600 "));
    }

    @Test
    void put_replacesScopeAndKeepsProtectedRecords() {
        StoredZone zone = globalZone("put.example");
        service.patchRecords(zone.getId(), null, RecordScope.ZONE, List.of(
                op("ADD", "old.put.example", "A", "192.0.2.1", 30)), null);

        List<Map<String, Object>> items = new ArrayList<>();
        for (StoredRecord record : rrset(zone, "put.example", "NS")) {
            items.add(Map.of("domain", record.getDomain(), "rtype", "NS", "rdata", record.getRdata(), "ttl", 86400));
        }
        items.add(Map.of("domain", "new.put.example", "rtype", "TXT", "rdata", "hello world", "ttl", 60));
        service.updateRecords(zone.getId(), null, RecordScope.ZONE, items, null);

        StoredZone updated = service.getZone(zone.getId(), null);
        assertTrue(rrset(updated, "old.put.example", "A").isEmpty());
        assertEquals("\"hello\" \"world\"", rrset(updated, "new.put.example", "TXT").getFirst().getRdata());
        assertEquals(1, rrset(updated, "put.example", "SOA").size());
        assertEquals(4, rrset(updated, "put.example", "NS").size());

        service.updateRecords(zone.getId(), null, new RecordScope("www.put.example", "CNAME"),
                List.of(Map.of("rdata", "target.example", "ttl", 30)), null);
        assertEquals("target.example.", rrset(updated, "www.put.example", "CNAME").getFirst().getRdata());
    }

    @Test
    void protectedRecords_rejectWrites() {
        StoredZone zone = globalZone("prot.example");

        assertError(400, "InvalidParameter", () -> service.patchRecords(zone.getId(), null, RecordScope.ZONE,
                List.of(op("ADD", "prot.example", "NS", "ns9.example", 30)), null));
        assertError(400, "InvalidParameter", () -> service.patchRecords(zone.getId(), null, RecordScope.ZONE,
                List.of(op("REMOVE", "prot.example", "SOA", null, null)), null));
        assertError(400, "InvalidParameter", () -> service.updateRecords(zone.getId(), null,
                new RecordScope("prot.example", "NS"), List.of(Map.of("rdata", "ns9.example", "ttl", 30)), null));
        assertError(400, "InvalidParameter", () -> service.deleteRecords(zone.getId(), null,
                new RecordScope("prot.example", "SOA"), null));
        assertError(400, "InvalidParameter", () -> service.patchRecords(zone.getId(), null, RecordScope.ZONE,
                List.of(op("ADD", "elsewhere.example", "A", "192.0.2.1", 30)), null));
    }

    @Test
    void rdata_isNormalizedTheWayTerraformExpects() {
        StoredZone zone = globalZone("norm.example");
        service.patchRecords(zone.getId(), null, RecordScope.ZONE, List.of(
                op("ADD", "v6.norm.example", "AAAA", "2001:0db8:85a3:0000:0000:8a2e:0370:7334", 30),
                op("ADD", "mapped.norm.example", "AAAA", "0:0:0:0:0:ffff:c000:201", 30),
                op("ADD", "alias.norm.example", "CNAME", "target.norm.example", 30),
                op("ADD", "txt.norm.example", "TXT", "v=spf1 \"quoted string\" ~all", 30),
                op("ADD", "mail.norm.example", "MX", "10 mx.norm.example", 30)), null);

        assertEquals("2001:db8:85a3::8a2e:370:7334", rrset(zone, "v6.norm.example", "AAAA").getFirst().getRdata());
        assertEquals("::ffff:192.0.2.1", rrset(zone, "mapped.norm.example", "AAAA").getFirst().getRdata());
        assertEquals("target.norm.example.", rrset(zone, "alias.norm.example", "CNAME").getFirst().getRdata());
        assertEquals("\"v=spf1\" \"quoted string\" \"~all\"",
                rrset(zone, "txt.norm.example", "TXT").getFirst().getRdata());
        assertEquals("10 mx.norm.example.", rrset(zone, "mail.norm.example", "MX").getFirst().getRdata());
        assertError(400, "InvalidParameter", () -> service.patchRecords(zone.getId(), null, RecordScope.ZONE,
                List.of(op("ADD", "bad.norm.example", "A", "not-an-ip", 30)), null));
    }

    @Test
    void deleteRrset_andDomain() {
        StoredZone zone = globalZone("del.example");
        service.patchRecords(zone.getId(), null, RecordScope.ZONE, List.of(
                op("ADD", "x.del.example", "A", "192.0.2.1", 30),
                op("ADD", "x.del.example", "TXT", "t", 30)), null);

        service.deleteRecords(zone.getId(), null, new RecordScope("x.del.example", "A"), null);
        assertTrue(rrset(zone, "x.del.example", "A").isEmpty());
        assertEquals(1, rrset(zone, "x.del.example", "TXT").size());
        service.deleteRecords(zone.getId(), null, new RecordScope("x.del.example", null), null);
        assertTrue(rrset(zone, "x.del.example", null).isEmpty());
        service.deleteRecords(zone.getId(), null, new RecordScope("del.example", null), null);
        assertEquals(5, rrset(zone, "del.example", null).size());
    }

    @Test
    void views_deleteWithZonesIsConflict() {
        StoredView view = service.createView(Map.of("compartmentId", COMPARTMENT, "displayName", "with-zones"));
        StoredZone zone = service.createZone(Map.of("name", "in.view", "compartmentId", COMPARTMENT,
                "scope", "PRIVATE", "viewId", view.getId()), null, null, null);

        assertError(409, "Conflict", () -> service.deleteView(view.getId(), null));
        service.deleteZone(zone.getId(), null, null);
        service.deleteView(view.getId(), null);
        assertError(404, "NotAuthorizedOrNotFound", () -> service.getView(view.getId()));
    }

    @Test
    void ifMatch_staleEtagIs412() {
        StoredZone zone = globalZone("etag.example");

        assertError(412, "NoEtagMatch", () -> service.patchRecords(zone.getId(), null, RecordScope.ZONE,
                List.of(op("ADD", "a.etag.example", "A", "192.0.2.1", 30)), "stale"));
        service.patchRecords(zone.getId(), null, RecordScope.ZONE,
                List.of(op("ADD", "a.etag.example", "A", "192.0.2.1", 30)), zone.getEtag());
    }

    @Test
    void vcnResolver_isCreatedOnceWithAProtectedDefaultView() {
        StoredResolver resolver = service.ensureVcnResolver("ocid1.vcn.oc1.iad.a", COMPARTMENT, "vcn-a");

        assertTrue(resolver.getId().startsWith("ocid1.dnsresolver.oc1.iad."));
        assertEquals("ocid1.vcn.oc1.iad.a", resolver.getAttachedVcnId());
        assertEquals("ACTIVE", resolver.getLifecycleState());
        StoredView view = service.getView(resolver.getDefaultViewId());
        assertTrue(view.isProtectedView());
        assertEquals("vcn-a", view.getDisplayName());
        assertEquals(resolver.getId(), service.ensureVcnResolver("ocid1.vcn.oc1.iad.a", COMPARTMENT, "vcn-a").getId());
        assertEquals(1, service.listResolvers(COMPARTMENT, null, null, null, null, null).size());

        assertError(400, "InvalidParameter", () -> service.deleteView(view.getId(), null));
        assertError(400, "InvalidParameter", () -> service.updateView(view.getId(), Map.of("displayName", "x"), null));
        assertError(400, "InvalidParameter", () -> service.createZone(Map.of("name", "a.oraclevcn.com",
                "compartmentId", COMPARTMENT, "scope", "PRIVATE", "viewId", view.getId()), null, null, null));
    }

    @Test
    void updateResolver_attachesViewsAndStoresForwardRules() {
        StoredResolver resolver = service.ensureVcnResolver("ocid1.vcn.oc1.iad.b", COMPARTMENT, "vcn-b");
        StoredView view = service.createView(Map.of("compartmentId", COMPARTMENT, "displayName", "shared"));

        assertError(400, "RelatedResourceNotAuthorizedOrNotFound", () -> service.updateResolver(resolver.getId(),
                Map.of("attachedViews", List.of(Map.of("viewId", "ocid1.dnsview.oc1.iad.missing"))), null));
        assertError(400, "InvalidParameter", () -> service.updateResolver(resolver.getId(),
                Map.of("rules", List.of(Map.of("action", "DROP"))), null));
        assertError(400, "MissingParameter", () -> service.updateResolver(resolver.getId(),
                Map.of("rules", List.of(Map.of("action", "FORWARD", "sourceEndpointName", "e"))), null));

        StoredResolver updated = service.updateResolver(resolver.getId(), Map.of(
                "displayName", "renamed",
                "attachedViews", List.of(Map.of("viewId", view.getId())),
                "rules", List.of(Map.of("action", "FORWARD", "destinationAddresses", List.of("10.0.0.2"),
                        "sourceEndpointName", "forwarder", "qnameCoverConditions", List.of("corp.example")))),
                resolver.getEtag());
        assertEquals("renamed", updated.getDisplayName());
        assertEquals(List.of(view.getId()), updated.getAttachedViewIds());
        assertEquals("FORWARD", updated.getRules().getFirst().get("action"));
        assertEquals(List.of(), updated.getRules().getFirst().get("clientAddressConditions"));
        assertError(409, "Conflict", () -> service.deleteView(view.getId(), null));
        assertError(412, "NoEtagMatch", () -> service.updateResolver(resolver.getId(), Map.of(), resolver.getEtag()
                + "-stale"));

        service.updateResolver(resolver.getId(), Map.of("attachedViews", List.of()), null);
        service.deleteView(view.getId(), null);
    }

    @Test
    void deleteVcnResolver_removesTheDefaultViewAndItsZones() {
        StoredResolver resolver = service.ensureVcnResolver("ocid1.vcn.oc1.iad.c", COMPARTMENT, "vcn-c");
        StoredZone zone = service.createZone(Map.of("name", "app.internal", "compartmentId", COMPARTMENT,
                "scope", "PRIVATE", "viewId", resolver.getDefaultViewId()), null, null, null);

        service.deleteVcnResolver("ocid1.vcn.oc1.iad.c");
        assertError(404, "NotAuthorizedOrNotFound", () -> service.getResolver(resolver.getId()));
        assertError(404, "NotAuthorizedOrNotFound", () -> service.getView(resolver.getDefaultViewId()));
        assertError(404, "NotAuthorizedOrNotFound", () -> service.getZone(zone.getId(), null));
        service.deleteVcnResolver("ocid1.vcn.oc1.iad.c");
    }

    @Test
    void resourcesIn_reportsZonesAndViews() {
        globalZone("usage.example");
        service.createView(Map.of("compartmentId", COMPARTMENT));

        assertEquals(2, service.resourcesIn(TENANCY, COMPARTMENT).size());
    }
}
