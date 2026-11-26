package io.floci.oci.services.dns;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.dns.DnsAnswer;
import io.floci.oci.core.common.dns.DnsRecord;
import io.floci.oci.core.common.dns.DnsRecordSource;
import io.floci.oci.services.dns.model.StoredRecord;
import io.floci.oci.services.dns.model.StoredZone;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Answers the embedded DNS server from OCI DNS zones, so the containers floci-oci launches resolve
 * the records users manage through the DNS API. Every ACTIVE zone of every tenancy, region and view
 * is consulted, GLOBAL and PRIVATE alike. The most specific zone covering a name answers it; when
 * several zones share that name, the oldest wins and the collision is logged at debug level.
 *
 * <p>A CNAME is followed through the zones floci-oci holds, up to {@link #MAX_CNAME_DEPTH} links;
 * each link is answered under its own owner name, the way a recursive resolver answers it.
 */
@ApplicationScoped
public class DnsZoneRecordSource implements DnsRecordSource {

    private static final Logger LOG = Logger.getLogger(DnsZoneRecordSource.class);

    static final int MAX_CNAME_DEPTH = 8;
    private static final int TYPE_A = 1;
    private static final int TYPE_NS = 2;
    private static final int TYPE_CNAME = 5;
    private static final int TYPE_SOA = 6;
    private static final int TYPE_TXT = 16;
    private static final int TYPE_AAAA = 28;

    private final DnsService service;
    private final EmulatorConfig config;

    @Inject
    public DnsZoneRecordSource(DnsService service, EmulatorConfig config) {
        this.service = service;
        this.config = config;
    }

    @Override
    public Optional<DnsAnswer> resolve(String name, int type) {
        if (name == null || name.isBlank() || !config.services().dns().enabled()) {
            return Optional.empty();
        }
        List<StoredZone> zones = service.allActiveZones().stream()
                .sorted(Comparator.comparing(StoredZone::getTimeCreated)).toList();
        String qname = DnsService.trimDot(name);
        Optional<StoredZone> zone = covering(zones, qname);
        if (zone.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(answer(zones, zone.get(), qname, type));
    }

    private DnsAnswer answer(List<StoredZone> zones, StoredZone first, String qname, int type) {
        List<DnsAnswer.Record> answers = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        StoredZone zone = first;
        String current = qname;
        for (int depth = 0; depth <= MAX_CNAME_DEPTH; depth++) {
            String owner = current;
            visited.add(owner.toLowerCase(Locale.ROOT));
            List<StoredRecord> atName = zone.getRecords().stream()
                    .filter(r -> r.getDomain().equalsIgnoreCase(owner)).toList();
            List<DnsAnswer.Record> matching = new ArrayList<>();
            for (StoredRecord record : atName) {
                if (wireType(record.getRtype()) == type) {
                    toWire(record).ifPresent(data -> matching.add(new DnsAnswer.Record(owner, record.getTtl(), data)));
                }
            }
            if (!matching.isEmpty()) {
                answers.addAll(matching);
                return DnsAnswer.of(answers);
            }
            Optional<StoredRecord> cname = atName.stream().filter(r -> "CNAME".equals(r.getRtype())).findFirst();
            if (cname.isEmpty()) {
                boolean exists = !atName.isEmpty() || hasNamesBeneath(zone, owner);
                return withSoa(new DnsAnswer(answers, exists, null), zone);
            }
            String target = DnsService.trimDot(cname.get().getRdata());
            answers.add(new DnsAnswer.Record(owner, cname.get().getTtl(), new DnsRecord.Cname(target)));
            Optional<StoredZone> next = covering(zones, target);
            if (next.isEmpty() || visited.contains(target.toLowerCase(Locale.ROOT))) {
                // The chain leaves the zones floci-oci holds, or loops: the CNAMEs found so far are the answer.
                return DnsAnswer.of(answers);
            }
            zone = next.get();
            current = target;
        }
        LOG.debugv("CNAME chain from {0} is longer than {1} links", qname, String.valueOf(MAX_CNAME_DEPTH));
        return DnsAnswer.of(answers);
    }

    /** The most specific zone holding {@code name}; the oldest of several that share that zone name. */
    private static Optional<StoredZone> covering(List<StoredZone> zones, String name) {
        StoredZone best = null;
        int collisions = 0;
        for (StoredZone zone : zones) {
            if (!DnsRdata.isWithin(name, zone.getName())) {
                continue;
            }
            if (best == null || zone.getName().length() > best.getName().length()) {
                best = zone;
                collisions = 0;
            } else if (zone.getName().equalsIgnoreCase(best.getName())) {
                collisions++;
            }
        }
        if (collisions > 0) {
            LOG.debugv("{0} zones are named {1}; {2} answers for {3}", String.valueOf(collisions + 1),
                    best.getName(), best.getId(), name);
        }
        return Optional.ofNullable(best);
    }

    private static boolean hasNamesBeneath(StoredZone zone, String name) {
        String suffix = "." + name.toLowerCase(Locale.ROOT);
        return zone.getName().equalsIgnoreCase(name) || zone.getRecords().stream()
                .anyMatch(r -> r.getDomain().toLowerCase(Locale.ROOT).endsWith(suffix));
    }

    private static DnsAnswer withSoa(DnsAnswer answer, StoredZone zone) {
        for (StoredRecord record : zone.getRecords()) {
            if ("SOA".equals(record.getRtype()) && record.getDomain().equalsIgnoreCase(zone.getName())) {
                try {
                    DnsRecord.Soa soa = new DnsRecord.Soa(record.getRdata());
                    soa.data();
                    return answer.withAuthority(zone.getName(), soa, record.getTtl());
                } catch (IllegalArgumentException e) {
                    LOG.debugv("SOA of zone {0} cannot be encoded: {1}", zone.getName(), e.getMessage());
                }
            }
        }
        return answer;
    }

    static int wireType(String rtype) {
        return switch (rtype) {
            case "A" -> TYPE_A;
            case "NS" -> TYPE_NS;
            case "CNAME" -> TYPE_CNAME;
            case "SOA" -> TYPE_SOA;
            case "TXT" -> TYPE_TXT;
            case "AAAA" -> TYPE_AAAA;
            default -> -1;
        };
    }

    private static Optional<DnsRecord> toWire(StoredRecord record) {
        try {
            DnsRecord data = switch (record.getRtype()) {
                case "A" -> new DnsRecord.Address(TYPE_A, record.getRdata());
                case "AAAA" -> new DnsRecord.Address(TYPE_AAAA, record.getRdata());
                case "CNAME" -> new DnsRecord.Cname(record.getRdata());
                case "NS" -> new DnsRecord.Ns(record.getRdata());
                case "TXT" -> new DnsRecord.Txt(record.getRdata());
                case "SOA" -> new DnsRecord.Soa(record.getRdata());
                default -> null;
            };
            if (data != null) {
                data.data();
            }
            return Optional.ofNullable(data);
        } catch (IllegalArgumentException e) {
            LOG.debugv("{0} record at {1} cannot be encoded: {2}", record.getRtype(), record.getDomain(), e.getMessage());
            return Optional.empty();
        }
    }
}
