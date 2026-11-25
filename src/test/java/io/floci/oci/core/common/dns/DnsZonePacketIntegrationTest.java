package io.floci.oci.core.common.dns;

import io.floci.oci.services.dns.DnsService;
import io.floci.oci.services.dns.DnsZoneRecordSource;
import io.floci.oci.services.dns.model.StoredView;
import io.floci.oci.services.dns.model.StoredZone;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OCI DNS zone records answered over UDP by the embedded DNS server, through
 * {@link DnsZoneRecordSource}. Ported from floci-aws Route53DnsPacketIntegrationTest.
 */
@QuarkusTest
class DnsZonePacketIntegrationTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..dnspackettest";

    @Inject
    DnsService dns;
    @Inject
    DnsZoneRecordSource source;
    @Inject
    Vertx vertx;

    private final List<String> zoneIds = new ArrayList<>();

    @AfterEach
    void deleteOwnedZones() {
        for (String id : zoneIds) {
            dns.deleteZone(id, null, null);
        }
        zoneIds.clear();
    }

    @Test
    void aRecordCarriesItsTtlAndIsAuthoritative() throws Exception {
        String zone = createZone(uniqueName());
        add(zone, "www." + zone, "A", "192.0.2.7", 120);

        byte[] request = DnsTestQuery.query("www." + zone, 1, 0x1234);
        byte[] response = query(request);
        assertHeader(response, 0x1234, 0, 1, 0);
        assertTrue((ByteBuffer.wrap(response).getShort(2) & 0x0400) != 0, "authoritative answer");
        ByteBuffer packet = ByteBuffer.wrap(response);
        packet.position(request.length);
        assertEquals("www." + zone, EmbeddedDnsServer.readName(packet, response));
        assertEquals(1, packet.getShort());
        assertEquals(1, packet.getShort());
        assertEquals(120, packet.getInt());
        assertEquals(4, packet.getShort());
        byte[] address = new byte[4];
        packet.get(address);
        assertArrayEquals(new byte[]{(byte) 192, 0, 2, 7}, address);
    }

    @Test
    void cnameChainIsAnsweredLinkByLink() throws Exception {
        String zone = createZone(uniqueName());
        add(zone, "alias." + zone, "CNAME", "middle." + zone, 30);
        add(zone, "middle." + zone, "CNAME", "target." + zone, 40);
        add(zone, "target." + zone, "A", "192.0.2.17", 600);

        byte[] request = DnsTestQuery.query("alias." + zone, 1, 0x0101);
        byte[] response = query(request);
        assertHeader(response, 0x0101, 0, 3, 0);
        ByteBuffer packet = ByteBuffer.wrap(response);
        packet.position(request.length);
        List<String> chain = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String owner = EmbeddedDnsServer.readName(packet, response);
            int type = packet.getShort();
            packet.getShort();
            int ttl = packet.getInt();
            int length = Short.toUnsignedInt(packet.getShort());
            int start = packet.position();
            String data = type == 5 ? EmbeddedDnsServer.readName(packet, response)
                    : (packet.get() & 0xFF) + "." + (packet.get() & 0xFF) + "." + (packet.get() & 0xFF) + "."
                    + (packet.get() & 0xFF);
            assertEquals(start + length, packet.position());
            chain.add(owner + " " + type + " " + ttl + " " + data);
        }
        assertEquals(List.of(
                "alias." + zone + " 5 30 middle." + zone,
                "middle." + zone + " 5 40 target." + zone,
                "target." + zone + " 1 600 192.0.2.17"), chain);

        byte[] cname = query(DnsTestQuery.query("alias." + zone, 5, 0x0102));
        assertHeader(cname, 0x0102, 0, 1, 0);
    }

    @Test
    void cnameToAMissingNameKeepsTheTargetsNxdomain() throws Exception {
        String zone = createZone(uniqueName());
        add(zone, "alias." + zone, "CNAME", "missing." + zone, 30);

        byte[] response = query(DnsTestQuery.query("alias." + zone, 1, 0x2222));
        assertHeader(response, 0x2222, 3, 1, 0);
    }

    @Test
    void negativeAnswersCarryTheZoneSoa() throws Exception {
        String zone = createZone(uniqueName());
        add(zone, "a.b." + zone, "A", "192.0.2.1", 60);
        add(zone, "txt." + zone, "TXT", "hello world", 60);

        byte[] nx = query(DnsTestQuery.query("absent." + zone, 1, 0x3333));
        assertHeader(nx, 0x3333, 3, 0, 1);
        ByteBuffer packet = ByteBuffer.wrap(nx);
        packet.position(DnsTestQuery.query("absent." + zone, 1, 0x3333).length);
        assertEquals(zone, EmbeddedDnsServer.readName(packet, nx));
        assertEquals(6, packet.getShort());
        packet.getShort();
        assertEquals(300, packet.getInt(), "the lesser of the SOA TTL (300) and its minimum (1800)");

        assertHeader(query(DnsTestQuery.query("txt." + zone, 1, 0x3334)), 0x3334, 0, 0, 1);
        assertHeader(query(DnsTestQuery.query("b." + zone, 1, 0x3335)), 0x3335, 0, 0, 1);

        byte[] request = DnsTestQuery.query("txt." + zone, 16, 0x3336);
        byte[] txt = query(request);
        assertHeader(txt, 0x3336, 0, 1, 0);
        byte[] expected = new DnsRecord.Txt("\"hello\" \"world\"").data();
        assertArrayEquals(expected, Arrays.copyOfRange(txt, txt.length - expected.length, txt.length));
    }

    @Test
    void apexAnswersItsProtectedSoaAndNs() throws Exception {
        String zone = createZone(uniqueName());

        assertHeader(query(DnsTestQuery.query(zone, 6, 0x4444)), 0x4444, 0, 1, 0);
        assertHeader(query(DnsTestQuery.query(zone, 2, 0x4445)), 0x4445, 0, 4, 0);
    }

    @Test
    void privateZoneRecordsResolveAndChangesAreSeenAtOnce() throws Exception {
        StoredView view = dns.createView(Map.of("compartmentId", COMPARTMENT, "displayName", "packet-view"));
        String name = uniqueName();
        StoredZone zone = dns.createZone(Map.of("name", name, "compartmentId", COMPARTMENT, "scope", "PRIVATE",
                "viewId", view.getId()), null, null, null);
        zoneIds.add(zone.getId());
        try {
            add(zone.getId(), "db." + name, "AAAA", "2001:db8::5", 45);
            byte[] response = query(DnsTestQuery.query("DB." + name, 28, 0x5555));
            assertHeader(response, 0x5555, 0, 1, 0);
            assertArrayEquals(new DnsRecord.Address(28, "2001:db8::5").data(),
                    Arrays.copyOfRange(response, response.length - 16, response.length));

            dns.deleteRecords(zone.getId(), null, new DnsService.RecordScope("db." + name, "AAAA"), null);
            assertHeader(query(DnsTestQuery.query("db." + name, 28, 0x5556)), 0x5556, 3, 0, 1);
        } finally {
            dns.deleteZone(zone.getId(), null, null);
            zoneIds.remove(zone.getId());
            dns.deleteView(view.getId(), null);
        }
    }

    @Test
    void ipv4MappedAaaaIsAnsweredAsSixteenBytes() throws Exception {
        String zone = createZone(uniqueName());
        add(zone, "mapped." + zone, "AAAA", "::FFFF:c000:0201", 45);
        add(zone, "compat." + zone, "AAAA", "::192.0.2.1", 45);

        List<String> echoed = dns.records(dns.getZone(zone, null), DnsService.RecordScope.ZONE,
                DnsService.RecordFilter.NONE).stream()
                .filter(r -> "AAAA".equals(r.getRtype())).map(r -> r.getDomain() + " " + r.getRdata()).toList();
        assertEquals(List.of("compat." + zone + " ::c000:201", "mapped." + zone + " ::ffff:192.0.2.1"), echoed);

        byte[] mapped = query(DnsTestQuery.query("mapped." + zone, 28, 0x6666));
        assertHeader(mapped, 0x6666, 0, 1, 0);
        assertEquals(16, ByteBuffer.wrap(mapped).getShort(mapped.length - 18), "RDLENGTH");
        assertArrayEquals(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 192, 0, 2, 1},
                Arrays.copyOfRange(mapped, mapped.length - 16, mapped.length));

        byte[] compat = query(DnsTestQuery.query("compat." + zone, 28, 0x6667));
        assertHeader(compat, 0x6667, 0, 1, 0);
        assertArrayEquals(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 192, 0, 2, 1},
                Arrays.copyOfRange(compat, compat.length - 16, compat.length));
    }

    @Test
    void namesOutsideEveryZoneAreLeftToTheUpstreams() {
        assertTrue(source.resolve("nothing-" + System.nanoTime() + ".invalid", 1).isEmpty());
        EmbeddedDnsServer server = new EmbeddedDnsServer(List.of(), List.of(source));
        assertTrue(server.resolveFromRecordSources("nothing.invalid", 1).isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String createZone(String name) {
        StoredZone zone = dns.createZone(Map.of("name", name, "compartmentId", COMPARTMENT), null, null, null);
        zoneIds.add(zone.getId());
        return name;
    }

    private void add(String zoneNameOrId, String domain, String rtype, String rdata, int ttl) {
        Map<String, Object> op = new HashMap<>();
        op.put("operation", "ADD");
        op.put("domain", domain);
        op.put("rtype", rtype);
        op.put("rdata", rdata);
        op.put("ttl", ttl);
        dns.patchRecords(zoneNameOrId, null, new DnsService.RecordScope(null, null), List.of(op), null);
    }

    private static String uniqueName() {
        return "packet" + System.nanoTime() + ".example";
    }

    private static void assertHeader(byte[] response, int txId, int rcode, int answers, int authorities) {
        ByteBuffer packet = ByteBuffer.wrap(response);
        assertEquals(txId, Short.toUnsignedInt(packet.getShort(0)));
        int flags = Short.toUnsignedInt(packet.getShort(2));
        assertTrue((flags & 0x8000) != 0, "QR set");
        assertEquals(rcode, flags & 0x0F, "RCODE");
        assertEquals(1, packet.getShort(4));
        assertEquals(answers, packet.getShort(6), "ANCOUNT");
        assertEquals(authorities, packet.getShort(8), "NSCOUNT");
    }

    private byte[] query(byte[] request) throws Exception {
        EmbeddedDnsServer server = new EmbeddedDnsServer(List.of(), List.of(source));
        DatagramSocket listener = vertx.createDatagramSocket(new DatagramSocketOptions().setIpV6(false));
        DatagramSocket client = vertx.createDatagramSocket(new DatagramSocketOptions().setIpV6(false));
        try {
            listener.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            listener.handler(packet -> server.handleQuery(vertx, listener, packet.data().getBytes(),
                    packet.sender().host(), packet.sender().port(), "127.0.0.1"));
            client.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            CompletableFuture<byte[]> received = new CompletableFuture<>();
            client.handler(packet -> received.complete(packet.data().getBytes()));
            client.send(Buffer.buffer(request), listener.localAddress().port(), "127.0.0.1")
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            return received.get(5, TimeUnit.SECONDS);
        } finally {
            listener.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
}
