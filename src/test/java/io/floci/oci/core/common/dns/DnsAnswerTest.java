package io.floci.oci.core.common.dns;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnsAnswerTest {

    private static DnsAnswer.Record a(String address, int ttl) {
        return new DnsAnswer.Record("host.example", ttl, new DnsRecord.Address(1, address));
    }

    @Test
    void negativeAnswersCarryNoRecords() {
        assertTrue(DnsAnswer.nxDomain().isEmpty());
        assertFalse(DnsAnswer.nxDomain().nameExists());
        assertEquals(3, DnsAnswer.nxDomain().responseCode());
        assertTrue(DnsAnswer.noData().isEmpty());
        assertTrue(DnsAnswer.noData().nameExists());
        assertEquals(0, DnsAnswer.noData().responseCode());
    }

    @Test
    void recordsMarkTheNameAsExisting() {
        DnsAnswer answer = DnsAnswer.of(List.of(a("10.0.0.1", 15)));

        assertFalse(answer.isEmpty());
        assertTrue(answer.nameExists());
        assertEquals(15, answer.records().getFirst().ttlSeconds());
        assertNull(answer.authority());
    }

    @Test
    void keepsATtlInsideTheRangeAnARecordCanCarry() {
        assertEquals(0, a("10.0.0.1", 0).ttlSeconds());
        assertEquals(Integer.MAX_VALUE, a("10.0.0.1", Integer.MAX_VALUE).ttlSeconds());
    }

    @Test
    void rejectsATtlNoResolverCouldActOn() {
        assertThrows(IllegalArgumentException.class, () -> a("10.0.0.1", -1));
    }

    @Test
    void doesNotShareTheCallersList() {
        List<DnsAnswer.Record> records = new ArrayList<>(List.of(a("10.0.0.1", 15)));
        DnsAnswer answer = DnsAnswer.of(records);
        records.add(a("10.0.0.2", 15));

        assertEquals(List.of("10.0.0.1"), answer.addresses());
        assertThrows(UnsupportedOperationException.class, () -> answer.records().add(a("10.0.0.3", 15)));
    }

    @Test
    void negativeTtlIsTheLesserOfTheSoaTtlAndItsMinimum() {
        DnsRecord.Soa soa = new DnsRecord.Soa("ns.example. host.example. 1 3600 600 604800 1800");

        assertEquals(300, DnsAnswer.nxDomain().withAuthority("example", soa, 300).authority().ttlSeconds());
        assertEquals(1800, DnsAnswer.noData().withAuthority("example", soa, 86400).authority().ttlSeconds());
        assertFalse(DnsAnswer.nxDomain().withAuthority("example", soa, 300).nameExists());
    }
}
