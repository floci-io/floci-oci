package io.floci.oci.core.common.dns;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DnsRecordTest {

    @Test
    void ipv6MappedIpv4StillEncodesSixteenBytes() {
        byte[] bytes = new DnsRecord.Address(28, "::ffff:192.0.2.1").data();
        assertEquals(16, bytes.length);
        assertArrayEquals(new byte[]{(byte) 192, 0, 2, 1}, new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
    }

    @Test
    void addressTypeMustMatchItsValue() {
        assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Address(1, "::1"));
        assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Address(28, "192.0.2.1"));
    }

    @Test
    void namesEncodeLabelsAndIgnoreOneTrailingDot() {
        assertArrayEquals(new byte[]{1, 'a', 1, 'b', 0}, DnsRecord.encodeName("a.b."));
        assertThrows(IllegalArgumentException.class, () -> DnsRecord.encodeName("a..b"));
        assertThrows(IllegalArgumentException.class, () -> DnsRecord.encodeName("a".repeat(64) + ".b"));
        assertThrows(IllegalArgumentException.class, () -> DnsRecord.encodeName("nonascii.\u00e9"));
    }

    @Test
    void ipv4ProjectionDoesNotIncludeNamesOrIpv6Values() {
        DnsAnswer answer = DnsAnswer.of(List.of(
                new DnsAnswer.Record("a.example", 15, new DnsRecord.Address(28, "::1")),
                new DnsAnswer.Record("a.example", 15, new DnsRecord.Cname("b.example"))));
        assertEquals(List.of(), answer.addresses());
        assertEquals(2, answer.records().size());
    }

    @Test
    void txtPreservesQuotedSegmentsEscapesAndEmptyStrings() {
        assertArrayEquals(new byte[]{3, 'a', ' ', 'b', 2, '"', '\\', 1, (byte) 255, 0},
                new DnsRecord.Txt("\"a b\" \"\\\"\\\\\" \"\\255\" \"\"").data());
        assertEquals(256, new DnsRecord.Txt("\"" + "x".repeat(255) + "\"").data().length);
        assertThrows(IllegalArgumentException.class,
                () -> new DnsRecord.Txt("\"" + "x".repeat(256) + "\"").data());
        assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Txt("\"\\256\"").data());
        assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Txt("\"unclosed").data());
    }

    @Test
    void txtEscapesAreThreeDecimalDigits() {
        assertArrayEquals(new byte[]{3, 9, (byte) 200, 'A'}, new DnsRecord.Txt("\"\\009\\200\\065\"").data());
        assertArrayEquals(new byte[]{4, 0, '1', '2', '3'}, new DnsRecord.Txt("\"\\000123\"").data());
        assertArrayEquals(new byte[]{1, 'x'}, new DnsRecord.Txt("\"\\x\"").data());
        assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Txt("\"\\99\"").data());
        assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Txt("\"\\9x9\"").data());
        assertThrows(IllegalArgumentException.class, () -> new DnsRecord.Txt("\"\\999\"").data());
    }

    @Test
    void soaEncodesNamesAndUnsignedFields() {
        byte[] bytes = new DnsRecord.Soa("ns.example. host.example. 4294967295 7200 900 1209600 30").data();
        ByteBuffer packet = ByteBuffer.wrap(bytes);
        assertEquals("ns.example", EmbeddedDnsServer.readName(packet, bytes));
        assertEquals("host.example", EmbeddedDnsServer.readName(packet, bytes));
        assertEquals(4294967295L, Integer.toUnsignedLong(packet.getInt()));
        assertEquals(7200, packet.getInt());
        assertEquals(900, packet.getInt());
        assertEquals(1209600, packet.getInt());
        assertEquals(30, packet.getInt());
        assertArrayEquals(DnsRecord.encodeName("ns.example."), new DnsRecord.Ns("ns.example.").data());
        assertArrayEquals(DnsRecord.encodeName("target.example"), new DnsRecord.Cname("target.example.").data());
    }
}
