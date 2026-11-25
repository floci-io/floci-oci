package io.floci.oci.core.common.dns;

import java.util.Optional;

/**
 * A source of records that {@link EmbeddedDnsServer} consults before forwarding a query upstream.
 * Implemented by services that hold DNS zones, so the DNS server stays independent of them: it
 * discovers implementations through CDI rather than importing one. Ported from floci-aws
 * core/common/dns.
 *
 * <p>An implementation answers only for names inside a zone it holds. An empty optional leaves the
 * query to the next source or the upstream resolvers; a present answer with no records is NODATA or
 * NXDOMAIN. It runs outside any request context.
 */
public interface DnsRecordSource {

    /**
     * Answers a query for {@code name} of wire type {@code type}. The name arrives without a
     * trailing dot and in the case the client sent.
     */
    Optional<DnsAnswer> resolve(String name, int type);
}
