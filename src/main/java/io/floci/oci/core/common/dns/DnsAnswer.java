package io.floci.oci.core.common.dns;

import java.util.List;

/**
 * What a {@link DnsRecordSource} answers for a name inside a zone it holds: the answer records, each
 * with its owner name and TTL, whether the final name exists, and the zone's SOA for the authority
 * section of a negative answer. Ported from floci-aws core/common/dns, with an owner per record so a
 * CNAME chain is answered the way a recursive resolver would: the CNAME at the query name, then the
 * records at its target.
 */
public record DnsAnswer(List<Record> records, boolean nameExists, Authority authority) {

    /** One answer-section record. */
    public record Record(String owner, int ttlSeconds, DnsRecord data) {

        public Record {
            // A TTL is an unsigned 31-bit field; RFC 2181 has a resolver read anything with the top
            // bit set as zero. A source picks its own fallback rather than putting one on the wire.
            if (ttlSeconds < 0) {
                throw new IllegalArgumentException("DNS TTL must not be negative: " + ttlSeconds);
            }
        }
    }

    /** The zone's SOA supplies the authority section and cache lifetime of negative answers. */
    public record Authority(String zone, DnsRecord.Soa soa, int ttlSeconds) {}

    private static final DnsAnswer NX_DOMAIN = new DnsAnswer(List.of(), false, null);
    private static final DnsAnswer NO_DATA = new DnsAnswer(List.of(), true, null);

    public DnsAnswer {
        records = List.copyOf(records);
    }

    public static DnsAnswer of(List<Record> records) {
        return new DnsAnswer(records, true, null);
    }

    /** An existing name without records of the requested type: NOERROR with no answers. */
    public static DnsAnswer noData() {
        return NO_DATA;
    }

    /** An absent name inside a held zone: NXDOMAIN. */
    public static DnsAnswer nxDomain() {
        return NX_DOMAIN;
    }

    /** RFC 2308: a negative answer is cached for the lesser of the SOA's own TTL and its MINIMUM field. */
    public DnsAnswer withAuthority(String zone, DnsRecord.Soa soa, int soaTtl) {
        int negativeTtl = (int) Math.min(soaTtl, soa.minimum());
        return new DnsAnswer(records, nameExists, new Authority(zone, soa, negativeTtl));
    }

    /** NOERROR, or NXDOMAIN (3) when the name the query ends at does not exist. */
    public int responseCode() {
        return nameExists ? 0 : 3;
    }

    /** IPv4 addresses among the answer records. */
    public List<String> addresses() {
        return records.stream().map(Record::data)
                .filter(data -> data instanceof DnsRecord.Address address && address.type() == 1)
                .map(data -> ((DnsRecord.Address) data).value()).toList();
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }
}
