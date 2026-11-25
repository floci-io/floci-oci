package io.floci.oci.services.dns;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.services.dns.model.StoredRecord;
import io.floci.oci.services.dns.model.StoredResolver;
import io.floci.oci.services.dns.model.StoredView;
import io.floci.oci.services.dns.model.StoredZone;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * OCI DNS, {@code /20180115} (oci-go-sdk dns/): zones, their records, and private views.
 *
 * <p>Zones are created {@code ACTIVE} with a protected apex SOA and NS record set. Every record write
 * that changes the zone bumps its serial (which is also its {@code version}), refreshes its etag and
 * stamps the new version on each RRSet that changed. A PATCH applies its operations to a copy and
 * stores the result only when every operation succeeded, so a failed {@code REQUIRE} or
 * {@code PROHIBIT} precondition (412) changes nothing.
 */
@ApplicationScoped
public class DnsService implements CompartmentUsage {

    private static final Logger LOG = Logger.getLogger(DnsService.class);

    static final String API_PREFIX = "/20180115";
    static final String ZONE_OCID_TYPE = "dns-zone";
    static final String VIEW_OCID_TYPE = "dnsview";
    static final String RESOLVER_OCID_TYPE = "dnsresolver";
    /** Apex nameservers floci-oci publishes: names under {@code localhost.floci.io}, which resolve to the emulator. */
    static final List<String> NAMESERVERS = List.of("ns1.dns.localhost.floci.io", "ns2.dns.localhost.floci.io",
            "ns3.dns.localhost.floci.io", "ns4.dns.localhost.floci.io");
    static final int SOA_TTL = 300;
    static final int NS_TTL = 86400;
    static final String GLOBAL = "GLOBAL";
    static final String PRIVATE = "PRIVATE";

    private static final Set<String> SCOPES = Set.of(GLOBAL, PRIVATE);
    private static final Set<String> RESOLUTION_MODES = Set.of("STATIC", "TRANSPARENT", "RTYPE_TRANSPARENT");
    private static final Set<String> OPERATIONS = Set.of("REQUIRE", "PROHIBIT", "ADD", "REMOVE");

    private final StorageBackend<String, StoredZone> zones;
    private final StorageBackend<String, StoredView> views;
    private final StorageBackend<String, StoredResolver> resolvers;
    private final EmulatorConfig config;
    private final OciContext ociContext;
    private final ServiceRegistry serviceRegistry;

    @Inject
    public DnsService(StorageFactory storageFactory, EmulatorConfig config, OciContext ociContext,
                      ServiceRegistry serviceRegistry) {
        this(storageFactory.create("dns", "dns-zones.json", new TypeReference<Map<String, StoredZone>>() {}),
                storageFactory.create("dns", "dns-views.json", new TypeReference<Map<String, StoredView>>() {}),
                storageFactory.create("dns", "dns-resolvers.json",
                        new TypeReference<Map<String, StoredResolver>>() {}),
                config, ociContext, serviceRegistry);
    }

    DnsService(StorageBackend<String, StoredZone> zones, StorageBackend<String, StoredView> views,
               StorageBackend<String, StoredResolver> resolvers, EmulatorConfig config, OciContext ociContext,
               ServiceRegistry serviceRegistry) {
        this.zones = zones;
        this.views = views;
        this.resolvers = resolvers;
        this.config = config;
        this.ociContext = ociContext != null ? ociContext : OciContext.fromConfig(config);
        this.serviceRegistry = serviceRegistry;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("dns")
                .enabled(config.services().dns().enabled())
                .storageKey("dns")
                .resourceClasses(DnsController.class)
                .build());
    }

    /** How a request addresses a zone by name: {@code scope}, {@code viewId} and {@code compartmentId} query parameters. */
    public record ZoneQuery(String scope, String viewId, String compartmentId) {
    }

    /** The records a record operation works on: the whole zone, one domain, or one RRSet. */
    public record RecordScope(String domain, String rtype) {

        static final RecordScope ZONE = new RecordScope(null, null);

        boolean covers(StoredRecord record) {
            return (domain == null || domain.equalsIgnoreCase(record.getDomain()))
                    && (rtype == null || rtype.equals(record.getRtype()));
        }
    }

    public record RecordFilter(String domain, String domainContains, String rtype, String sortBy, String sortOrder) {

        static final RecordFilter NONE = new RecordFilter(null, null, null, null, null);
    }

    // ── Zones ──────────────────────────────────────────────────────────────────

    public synchronized StoredZone createZone(Map<String, Object> body, String queryScope, String queryViewId,
                                              String queryCompartmentId) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String migrationSource = str(b, "migrationSource");
        if (migrationSource != null && !"NONE".equals(migrationSource)) {
            throw OciException.notImplemented("Zone migration from " + migrationSource + " is not emulated");
        }
        String name = DnsRdata.domainName(str(b, "name"), "name");
        String compartmentId = firstNonNull(str(b, "compartmentId"), queryCompartmentId);
        if (compartmentId == null) {
            throw OciException.missingParameter("compartmentId is required");
        }
        String scope = enumValue(firstNonNull(str(b, "scope"), queryScope), SCOPES, "scope", GLOBAL);
        String viewId = firstNonNull(str(b, "viewId"), queryViewId);
        String zoneType = enumValue(str(b, "zoneType"), Set.of("PRIMARY", "SECONDARY"), "zoneType", "PRIMARY");
        if ("SECONDARY".equals(zoneType)) {
            throw OciException.notImplemented("Secondary zones are not emulated");
        }
        String dnssecState = enumValue(str(b, "dnssecState"), Set.of("ENABLED", "DISABLED"), "dnssecState", "DISABLED");
        if ("ENABLED".equals(dnssecState)) {
            throw OciException.notImplemented("DNSSEC is not emulated");
        }
        String resolutionMode = enumValue(str(b, "resolutionMode"), RESOLUTION_MODES, "resolutionMode", "STATIC");
        if (PRIVATE.equals(scope)) {
            if (viewId == null) {
                throw OciException.missingParameter("viewId is required for a PRIVATE zone");
            }
            StoredView view = requireView(viewId);
            if (view.isProtectedView() && DnsRdata.isWithin(name, "oraclevcn.com")) {
                throw OciException.invalidParameter("A private zone at or under oraclevcn.com cannot be created "
                        + "in the default protected view of a VCN-dedicated resolver");
            }
        } else if (viewId != null) {
            throw OciException.invalidParameter("viewId applies to PRIVATE zones only");
        }
        boolean taken = zones.scan(k -> true).stream()
                .anyMatch(z -> z.getName().equalsIgnoreCase(name) && z.getScope().equals(scope)
                        && Objects.equals(z.getViewId(), viewId));
        if (taken) {
            throw OciException.conflict("A zone named " + name + " already exists"
                    + (viewId == null ? "" : " in view " + viewId));
        }
        StoredZone zone = new StoredZone();
        zone.setId(Ocids.generate(ZONE_OCID_TYPE, ociContext.realm(), ociContext.regionCode()));
        zone.setName(name);
        zone.setCompartmentId(compartmentId);
        zone.setScope(scope);
        zone.setZoneType(zoneType);
        zone.setViewId(viewId);
        zone.setResolutionMode(resolutionMode);
        zone.setDnssecState(dnssecState);
        zone.setLifecycleState("ACTIVE");
        zone.setTimeCreated(Instant.now().toString());
        zone.setSerial(1);
        zone.setEtag(Etags.newEtag());
        zone.setFreeformTags(tags(b.get("freeformTags")));
        zone.setDefinedTags(tags(b.get("definedTags")));
        zone.setRecords(apexRecords(name, 1));
        zones.put(zone.getId(), zone);
        LOG.infov("createZone {0} {1} ({2})", scope, name, zone.getId());
        return zone;
    }

    /** The protected SOA and NS record sets every zone is created with. */
    private static List<StoredRecord> apexRecords(String name, long serial) {
        List<StoredRecord> records = new ArrayList<>();
        records.add(new StoredRecord(name, "SOA", soaRdata(name, serial), SOA_TTL, true));
        for (String nameserver : NAMESERVERS) {
            records.add(new StoredRecord(name, "NS", nameserver + ".", NS_TTL, true));
        }
        for (StoredRecord record : records) {
            record.setRrsetVersion(String.valueOf(serial));
            rehash(record);
        }
        return records;
    }

    static String soaRdata(String zoneName, long serial) {
        return NAMESERVERS.getFirst() + ". hostmaster." + zoneName + ". " + serial + " 3600 600 604800 1800";
    }

    public StoredZone getZone(String zoneNameOrId, ZoneQuery query) {
        if (zoneNameOrId != null && zoneNameOrId.startsWith("ocid1.")) {
            return zones.get(zoneNameOrId).orElseThrow(() -> zoneNotFound(zoneNameOrId));
        }
        String name = DnsRdata.domainName(zoneNameOrId, "zoneNameOrId");
        ZoneQuery q = query == null ? new ZoneQuery(null, null, null) : query;
        String scope = q.scope() != null ? enumValue(q.scope(), SCOPES, "scope", GLOBAL)
                : q.viewId() != null ? PRIVATE : GLOBAL;
        if (PRIVATE.equals(scope) && q.viewId() == null) {
            throw OciException.missingParameter("viewId is required when a PRIVATE zone is addressed by name");
        }
        return zones.scan(k -> true).stream()
                .filter(z -> z.getName().equalsIgnoreCase(name) && z.getScope().equals(scope))
                .filter(z -> q.viewId() == null || q.viewId().equals(z.getViewId()))
                .filter(z -> q.compartmentId() == null || q.compartmentId().equals(z.getCompartmentId()))
                .findFirst()
                .orElseThrow(() -> zoneNotFound(zoneNameOrId));
    }

    public List<StoredZone> listZones(String compartmentId, String name, String nameContains, String zoneType,
                                      String createdFrom, String createdBefore, String lifecycleState, String scope,
                                      String viewId, String sortBy, String sortOrder) {
        Instant from = createdFrom == null ? null : time(createdFrom, "timeCreatedGreaterThanOrEqualTo");
        Instant before = createdBefore == null ? null : time(createdBefore, "timeCreatedLessThan");
        Comparator<StoredZone> order = switch (sortBy == null ? "timeCreated" : sortBy) {
            case "name" -> Comparator.comparing(z -> z.getName().toLowerCase(Locale.ROOT));
            case "zoneType" -> Comparator.comparing(StoredZone::getZoneType);
            case "timeCreated" -> Comparator.comparing(StoredZone::getTimeCreated);
            default -> throw OciException.invalidParameter("sortBy must be name, zoneType or timeCreated");
        };
        return zones.scan(k -> true).stream()
                .filter(z -> compartmentId.equals(z.getCompartmentId()))
                .filter(z -> name == null || z.getName().equalsIgnoreCase(trimDot(name)))
                .filter(z -> nameContains == null
                        || z.getName().toLowerCase(Locale.ROOT).contains(nameContains.toLowerCase(Locale.ROOT)))
                .filter(z -> zoneType == null || zoneType.equals(z.getZoneType()))
                .filter(z -> from == null || !Instant.parse(z.getTimeCreated()).isBefore(from))
                .filter(z -> before == null || Instant.parse(z.getTimeCreated()).isBefore(before))
                .filter(z -> lifecycleState == null || lifecycleState.equals(z.getLifecycleState()))
                .filter(z -> scope == null || scope.equals(z.getScope()))
                .filter(z -> viewId == null || viewId.equals(z.getViewId()))
                .sorted("DESC".equals(sortOrder) ? order.reversed() : order)
                .toList();
    }

    public synchronized StoredZone updateZone(String zoneNameOrId, ZoneQuery query, Map<String, Object> body,
                                              String ifMatch) {
        StoredZone zone = getZone(zoneNameOrId, query);
        Etags.checkIfMatch(ifMatch, zone.getEtag());
        requireUnprotected(zone);
        Map<String, Object> b = body == null ? Map.of() : body;
        if (str(b, "dnssecState") != null
                && "ENABLED".equals(enumValue(str(b, "dnssecState"), Set.of("ENABLED", "DISABLED"), "dnssecState", null))) {
            throw OciException.notImplemented("DNSSEC is not emulated");
        }
        if (str(b, "resolutionMode") != null) {
            zone.setResolutionMode(enumValue(str(b, "resolutionMode"), RESOLUTION_MODES, "resolutionMode", null));
        }
        if (b.get("freeformTags") instanceof Map<?, ?>) {
            zone.setFreeformTags(tags(b.get("freeformTags")));
        }
        if (b.get("definedTags") instanceof Map<?, ?>) {
            zone.setDefinedTags(tags(b.get("definedTags")));
        }
        zone.setEtag(Etags.newEtag());
        zones.put(zone.getId(), zone);
        return zone;
    }

    public synchronized void deleteZone(String zoneNameOrId, ZoneQuery query, String ifMatch) {
        StoredZone zone = getZone(zoneNameOrId, query);
        Etags.checkIfMatch(ifMatch, zone.getEtag());
        if (zone.isProtectedZone()) {
            throw OciException.invalidParameter("Protected zone " + zone.getName() + " cannot be deleted");
        }
        zones.delete(zone.getId());
        LOG.infov("deleteZone {0} ({1})", zone.getName(), zone.getId());
    }

    public synchronized void changeZoneCompartment(String zoneId, String compartmentId, String ifMatch) {
        StoredZone zone = getZone(zoneId, null);
        Etags.checkIfMatch(ifMatch, zone.getEtag());
        if (zone.isProtectedZone()) {
            throw OciException.invalidParameter("Protected zone " + zone.getName() + " cannot change compartment");
        }
        if (compartmentId == null) {
            throw OciException.missingParameter("compartmentId is required");
        }
        zone.setCompartmentId(compartmentId);
        zone.setEtag(Etags.newEtag());
        zones.put(zone.getId(), zone);
    }

    // ── Records ────────────────────────────────────────────────────────────────

    /** A zone's records and the etag they carry, read together so a concurrent write cannot pair them up wrongly. */
    public record RecordSnapshot(List<StoredRecord> records, String etag) {
    }

    /**
     * The zone's current records and etag under the lock {@link #commit} holds. A commit publishes a new
     * unmodifiable list of fresh records and never changes a published one, so the snapshot stays valid.
     */
    public synchronized RecordSnapshot snapshot(StoredZone zone) {
        return new RecordSnapshot(zone.getRecords(), zone.getEtag());
    }

    public List<StoredRecord> records(StoredZone zone, RecordScope scope, RecordFilter filter) {
        return records(zone.getRecords(), scope, filter);
    }

    public List<StoredRecord> records(List<StoredRecord> records, RecordScope scope, RecordFilter filter) {
        RecordFilter f = filter == null ? RecordFilter.NONE : filter;
        Comparator<StoredRecord> order = switch (f.sortBy() == null ? "domain" : f.sortBy()) {
            case "domain" -> Comparator.comparing((StoredRecord r) -> r.getDomain().toLowerCase(Locale.ROOT))
                    .thenComparing(StoredRecord::getRtype);
            case "rtype" -> Comparator.comparing(StoredRecord::getRtype)
                    .thenComparing(r -> r.getDomain().toLowerCase(Locale.ROOT));
            case "ttl" -> Comparator.comparingInt(StoredRecord::getTtl)
                    .thenComparing(r -> r.getDomain().toLowerCase(Locale.ROOT));
            default -> throw OciException.invalidParameter("sortBy must be domain, rtype or ttl");
        };
        order = order.thenComparing(StoredRecord::getRdata);
        String domain = f.domain() == null ? null : trimDot(f.domain());
        String rtype = f.rtype() == null ? null : f.rtype().toUpperCase(Locale.ROOT);
        return records.stream()
                .filter(scope::covers)
                .filter(r -> domain == null || r.getDomain().equalsIgnoreCase(domain))
                .filter(r -> f.domainContains() == null || r.getDomain().toLowerCase(Locale.ROOT)
                        .contains(f.domainContains().toLowerCase(Locale.ROOT)))
                .filter(r -> rtype == null || rtype.equals(r.getRtype()))
                .sorted("DESC".equals(f.sortOrder()) ? order.reversed() : order)
                .toList();
    }

    public synchronized StoredZone patchRecords(String zoneNameOrId, ZoneQuery query, RecordScope scope,
                                                Object items, String ifMatch) {
        StoredZone zone = getZone(zoneNameOrId, query);
        Etags.checkIfMatch(ifMatch, zone.getEtag());
        List<StoredRecord> working = copyOf(zone.getRecords());
        for (Map<String, Object> op : maps(items)) {
            applyOperation(zone, scope, working, op);
        }
        commit(zone, working);
        return zone;
    }

    private void applyOperation(StoredZone zone, RecordScope scope, List<StoredRecord> working,
                                Map<String, Object> op) {
        String operation = str(op, "operation");
        if (operation == null) {
            throw OciException.missingParameter("Every record operation needs an operation");
        }
        if (!OPERATIONS.contains(operation)) {
            throw OciException.invalidParameter("operation must be REQUIRE, PROHIBIT, ADD or REMOVE, got " + operation);
        }
        String domain = scoped(zone, scope.domain(), str(op, "domain"), "domain");
        String rtype = scopedRtype(scope.rtype(), str(op, "rtype"));
        String rdata = str(op, "rdata");
        String normalized = rdata != null && rtype != null ? DnsRdata.normalize(rtype, rdata) : null;
        String recordHash = str(op, "recordHash");
        Integer ttl = ttl(op.get("ttl"), false);
        List<StoredRecord> matches = working.stream()
                .filter(r -> recordHash == null || recordHash.equals(r.getRecordHash()))
                .filter(r -> domain == null || domain.equalsIgnoreCase(r.getDomain()))
                .filter(r -> rtype == null || rtype.equals(r.getRtype()))
                .filter(r -> rdata == null || rdataMatches(r, normalized, rdata))
                .toList();
        switch (operation) {
            case "REQUIRE" -> {
                if (matches.isEmpty()) {
                    throw OciException.noEtagMatch("REQUIRE precondition failed: no record matches "
                            + describe(domain, rtype, rdata));
                }
            }
            case "PROHIBIT" -> {
                if (!matches.isEmpty()) {
                    throw OciException.noEtagMatch("PROHIBIT precondition failed: a record matches "
                            + describe(domain, rtype, rdata));
                }
            }
            case "REMOVE" -> {
                if (matches.stream().anyMatch(StoredRecord::isProtectedRecord)) {
                    throw protectedRecords(matches.getFirst().getDomain(), matches.getFirst().getRtype());
                }
                working.removeAll(matches);
            }
            default -> add(working, domain, rtype, normalized, ttl);
        }
    }

    private static void add(List<StoredRecord> working, String domain, String rtype, String rdata, Integer ttl) {
        if (domain == null) {
            throw OciException.missingParameter("ADD needs a domain");
        }
        if (rtype == null) {
            throw OciException.missingParameter("ADD needs an rtype");
        }
        requireUnprotectedRrset(working, domain, rtype);
        List<StoredRecord> rrset = working.stream()
                .filter(r -> domain.equalsIgnoreCase(r.getDomain()) && rtype.equals(r.getRtype())).toList();
        if (rdata == null) {
            if (ttl == null) {
                throw OciException.missingParameter("ADD needs rdata, or a ttl to apply to the RRSet");
            }
            rrset.forEach(r -> r.setTtl(ttl));
            return;
        }
        StoredRecord existing = rrset.stream().filter(r -> r.getRdata().equals(rdata)).findFirst().orElse(null);
        if (existing != null) {
            if (ttl != null) {
                existing.setTtl(ttl);
            }
            return;
        }
        if (ttl == null) {
            throw OciException.missingParameter("ttl is required to add a record");
        }
        working.add(new StoredRecord(domain, rtype, rdata, ttl, false));
    }

    public synchronized StoredZone updateRecords(String zoneNameOrId, ZoneQuery query, RecordScope scope,
                                                 Object items, String ifMatch) {
        StoredZone zone = getZone(zoneNameOrId, query);
        Etags.checkIfMatch(ifMatch, zone.getEtag());
        List<StoredRecord> current = zone.getRecords();
        Map<String, StoredRecord> replacements = new LinkedHashMap<>();
        for (Map<String, Object> item : maps(items)) {
            String domain = scoped(zone, scope.domain(), str(item, "domain"), "domain");
            String rtype = scopedRtype(scope.rtype(), str(item, "rtype"));
            if (domain == null) {
                throw OciException.missingParameter("Every record needs a domain");
            }
            if (rtype == null) {
                throw OciException.missingParameter("Every record needs an rtype");
            }
            String rdata = DnsRdata.normalize(rtype, str(item, "rdata"));
            int ttl = ttl(item.get("ttl"), true);
            boolean protectedRrset = current.stream().anyMatch(r -> r.isProtectedRecord()
                    && domain.equalsIgnoreCase(r.getDomain()) && rtype.equals(r.getRtype()));
            if (protectedRrset) {
                boolean unchanged = current.stream().anyMatch(r -> r.isProtectedRecord()
                        && domain.equalsIgnoreCase(r.getDomain()) && rtype.equals(r.getRtype())
                        && rdata.equals(r.getRdata()));
                if (!unchanged) {
                    throw protectedRecords(domain, rtype);
                }
                continue;
            }
            replacements.put(domain.toLowerCase(Locale.ROOT) + "|" + rtype + "|" + rdata,
                    new StoredRecord(domain, rtype, rdata, ttl, false));
        }
        List<StoredRecord> working = new ArrayList<>();
        for (StoredRecord record : current) {
            if (!scope.covers(record) || record.isProtectedRecord()) {
                working.add(record.copy());
            }
        }
        working.addAll(replacements.values());
        commit(zone, working);
        return zone;
    }

    public synchronized void deleteRecords(String zoneNameOrId, ZoneQuery query, RecordScope scope, String ifMatch) {
        StoredZone zone = getZone(zoneNameOrId, query);
        Etags.checkIfMatch(ifMatch, zone.getEtag());
        String domain = scoped(zone, scope.domain(), null, "domain");
        RecordScope normalized = new RecordScope(domain, scope.rtype() == null ? null : DnsRdata.rtype(scope.rtype()));
        if (normalized.rtype() != null) {
            requireUnprotectedRrset(zone.getRecords(), domain, normalized.rtype());
        }
        List<StoredRecord> working = new ArrayList<>();
        for (StoredRecord record : zone.getRecords()) {
            if (!normalized.covers(record) || record.isProtectedRecord()) {
                working.add(record.copy());
            }
        }
        commit(zone, working);
    }

    /**
     * Stores {@code working} as the zone's records when they differ from the current ones: the serial goes
     * up by one, each RRSet that changed carries the new version, and the SOA shows the new serial.
     */
    private void commit(StoredZone zone, List<StoredRecord> working) {
        Map<String, List<String>> before = rrsets(zone.getRecords());
        Map<String, List<String>> after = rrsets(working);
        if (before.equals(after)) {
            return;
        }
        long serial = zone.getSerial() + 1;
        String version = String.valueOf(serial);
        for (StoredRecord record : working) {
            String key = rrsetKey(record);
            if (!Objects.equals(before.get(key), after.get(key)) || "SOA".equals(record.getRtype())) {
                record.setRrsetVersion(version);
            }
            if ("SOA".equals(record.getRtype()) && record.isProtectedRecord()) {
                record.setRdata(soaRdata(zone.getName(), serial));
            }
            rehash(record);
        }
        zone.setRecords(List.copyOf(working));
        zone.setSerial(serial);
        zone.setEtag(Etags.newEtag());
        zones.put(zone.getId(), zone);
    }

    private static Map<String, List<String>> rrsets(List<StoredRecord> records) {
        Map<String, List<String>> sets = new HashMap<>();
        for (StoredRecord record : records) {
            sets.computeIfAbsent(rrsetKey(record), k -> new ArrayList<>()).add(record.getRdata() + "|" + record.getTtl());
        }
        sets.values().forEach(values -> values.sort(Comparator.naturalOrder()));
        return sets;
    }

    private static String rrsetKey(StoredRecord record) {
        return record.getDomain().toLowerCase(Locale.ROOT) + "|" + record.getRtype();
    }

    private static void rehash(StoredRecord record) {
        record.setRecordHash(DnsRdata.recordHash(record.getDomain(), record.getRtype(), record.getRdata(),
                record.getTtl()));
    }

    private static boolean rdataMatches(StoredRecord record, String normalized, String raw) {
        if (normalized != null) {
            return normalized.equals(record.getRdata());
        }
        try {
            return DnsRdata.normalize(record.getRtype(), raw).equals(record.getRdata());
        } catch (OciException expected) {
            // rdata that is not valid for this record's type cannot describe it.
            return false;
        }
    }

    private static void requireUnprotectedRrset(List<StoredRecord> records, String domain, String rtype) {
        boolean protectedRrset = records.stream().anyMatch(r -> r.isProtectedRecord()
                && domain.equalsIgnoreCase(r.getDomain()) && rtype.equals(r.getRtype()));
        if (protectedRrset) {
            throw protectedRecords(domain, rtype);
        }
    }

    private static OciException protectedRecords(String domain, String rtype) {
        return OciException.invalidParameter("The " + rtype + " records at " + domain
                + " are protected and cannot be changed");
    }

    /** A record's domain: the path's when the request is scoped to one, which a body value must then repeat. */
    private static String scoped(StoredZone zone, String pathValue, String bodyValue, String field) {
        String value = bodyValue != null ? DnsRdata.domainName(bodyValue, field)
                : pathValue != null ? DnsRdata.domainName(pathValue, field) : null;
        if (value == null) {
            return null;
        }
        if (pathValue != null && !trimDot(pathValue).equalsIgnoreCase(value)) {
            throw OciException.invalidParameter(field + " " + bodyValue + " does not match the path domain " + pathValue);
        }
        if (!DnsRdata.isWithin(value, zone.getName())) {
            throw OciException.invalidParameter("Domain " + value + " is not within zone " + zone.getName());
        }
        return value;
    }

    private static String scopedRtype(String pathValue, String bodyValue) {
        String value = bodyValue != null ? DnsRdata.rtype(bodyValue) : pathValue != null ? DnsRdata.rtype(pathValue) : null;
        if (pathValue != null && value != null && !value.equals(DnsRdata.rtype(pathValue))) {
            throw OciException.invalidParameter("rtype " + bodyValue + " does not match the path rtype " + pathValue);
        }
        return value;
    }

    private static String describe(String domain, String rtype, String rdata) {
        return Stream.of(domain, rtype, rdata).filter(Objects::nonNull).reduce((a, b) -> a + " " + b)
                .orElse("the operation");
    }

    private static List<StoredRecord> copyOf(List<StoredRecord> records) {
        List<StoredRecord> copy = new ArrayList<>();
        for (StoredRecord record : records) {
            copy.add(record.copy());
        }
        return copy;
    }

    // ── Views ──────────────────────────────────────────────────────────────────

    public synchronized StoredView createView(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String compartmentId = str(b, "compartmentId");
        if (compartmentId == null) {
            throw OciException.missingParameter("compartmentId is required");
        }
        StoredView view = newView(compartmentId, Objects.requireNonNullElse(str(b, "displayName"),
                "view" + System.currentTimeMillis()), false);
        view.setFreeformTags(tags(b.get("freeformTags")));
        view.setDefinedTags(tags(b.get("definedTags")));
        views.put(view.getId(), view);
        LOG.infov("createView {0} ({1})", view.getDisplayName(), view.getId());
        return view;
    }

    StoredView newView(String compartmentId, String displayName, boolean protectedView) {
        StoredView view = new StoredView();
        view.setId(Ocids.generate(VIEW_OCID_TYPE, ociContext.realm(), ociContext.regionCode()));
        view.setCompartmentId(compartmentId);
        view.setDisplayName(displayName);
        view.setLifecycleState("ACTIVE");
        String now = Instant.now().toString();
        view.setTimeCreated(now);
        view.setTimeUpdated(now);
        view.setEtag(Etags.newEtag());
        view.setProtectedView(protectedView);
        return view;
    }

    public StoredView getView(String viewId) {
        return views.get(viewId).orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: view " + viewId));
    }

    public List<StoredView> listViews(String compartmentId, String displayName, String id, String lifecycleState,
                                      String sortBy, String sortOrder) {
        Comparator<StoredView> order = switch (sortBy == null ? "timeCreated" : sortBy) {
            case "displayName" -> Comparator.comparing(v -> v.getDisplayName().toLowerCase(Locale.ROOT));
            case "timeCreated" -> Comparator.comparing(StoredView::getTimeCreated);
            default -> throw OciException.invalidParameter("sortBy must be displayName or timeCreated");
        };
        return views.scan(k -> true).stream()
                .filter(v -> compartmentId.equals(v.getCompartmentId()))
                .filter(v -> displayName == null || displayName.equals(v.getDisplayName()))
                .filter(v -> id == null || id.equals(v.getId()))
                .filter(v -> lifecycleState == null || lifecycleState.equals(v.getLifecycleState()))
                .sorted("DESC".equals(sortOrder) ? order.reversed() : order)
                .toList();
    }

    public synchronized StoredView updateView(String viewId, Map<String, Object> body, String ifMatch) {
        StoredView view = getView(viewId);
        Etags.checkIfMatch(ifMatch, view.getEtag());
        requireUnprotected(view, "updated");
        Map<String, Object> b = body == null ? Map.of() : body;
        if (str(b, "displayName") != null) {
            view.setDisplayName(str(b, "displayName"));
        }
        if (b.get("freeformTags") instanceof Map<?, ?>) {
            view.setFreeformTags(tags(b.get("freeformTags")));
        }
        if (b.get("definedTags") instanceof Map<?, ?>) {
            view.setDefinedTags(tags(b.get("definedTags")));
        }
        touch(view);
        views.put(viewId, view);
        return view;
    }

    public synchronized void deleteView(String viewId, String ifMatch) {
        StoredView view = getView(viewId);
        Etags.checkIfMatch(ifMatch, view.getEtag());
        requireUnprotected(view, "deleted");
        boolean hasZones = zones.scan(k -> true).stream().anyMatch(z -> viewId.equals(z.getViewId()));
        if (hasZones) {
            throw OciException.conflict("View " + viewId + " still contains zones; delete them first");
        }
        boolean attached = resolvers.scan(k -> true).stream().anyMatch(r -> r.getAttachedViewIds().contains(viewId));
        if (attached) {
            throw OciException.conflict("View " + viewId + " is still attached to a resolver; detach it first");
        }
        views.delete(viewId);
        LOG.infov("deleteView {0}", viewId);
    }

    public synchronized void changeViewCompartment(String viewId, String compartmentId, String ifMatch) {
        StoredView view = getView(viewId);
        Etags.checkIfMatch(ifMatch, view.getEtag());
        requireUnprotected(view, "moved");
        if (compartmentId == null) {
            throw OciException.missingParameter("compartmentId is required");
        }
        view.setCompartmentId(compartmentId);
        touch(view);
        views.put(viewId, view);
    }

    private StoredView requireView(String viewId) {
        return views.get(viewId).filter(v -> "ACTIVE".equals(v.getLifecycleState()))
                .orElseThrow(() -> OciException.relatedResourceNotAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: view " + viewId));
    }

    private static void requireUnprotected(StoredView view, String verb) {
        if (view.isProtectedView()) {
            throw OciException.invalidParameter("Protected view " + view.getId() + " cannot be " + verb);
        }
    }

    private static void requireUnprotected(StoredZone zone) {
        if (zone.isProtectedZone()) {
            throw OciException.invalidParameter("Protected zone " + zone.getName() + " cannot be updated");
        }
    }

    private static void touch(StoredView view) {
        view.setTimeUpdated(Instant.now().toString());
        view.setEtag(Etags.newEtag());
    }

    // ── Resolvers ──────────────────────────────────────────────────────────────

    /**
     * The resolver dedicated to a VCN, with its protected default view: created with the VCN, and on
     * first use for a VCN that predates the DNS service.
     */
    public synchronized StoredResolver ensureVcnResolver(String vcnId, String compartmentId, String displayName) {
        Optional<StoredResolver> existing = resolverForVcn(vcnId);
        if (existing.isPresent()) {
            return existing.get();
        }
        StoredView view = newView(compartmentId, displayName, true);
        views.put(view.getId(), view);
        StoredResolver resolver = new StoredResolver();
        resolver.setId(Ocids.generate(RESOLVER_OCID_TYPE, ociContext.realm(), ociContext.regionCode()));
        resolver.setCompartmentId(compartmentId);
        resolver.setDisplayName(displayName);
        resolver.setAttachedVcnId(vcnId);
        resolver.setDefaultViewId(view.getId());
        resolver.setLifecycleState("ACTIVE");
        String now = Instant.now().toString();
        resolver.setTimeCreated(now);
        resolver.setTimeUpdated(now);
        resolver.setEtag(Etags.newEtag());
        resolvers.put(resolver.getId(), resolver);
        LOG.infov("Created resolver {0} and default view {1} for VCN {2}", resolver.getId(), view.getId(), vcnId);
        return resolver;
    }

    /**
     * Removes a deleted VCN's resolver, its default view, and the zones in that view. Other resolvers
     * that attached the view stop attaching it, so none keeps naming a deleted view.
     */
    public synchronized void deleteVcnResolver(String vcnId) {
        Optional<StoredResolver> resolver = resolverForVcn(vcnId);
        if (resolver.isEmpty()) {
            return;
        }
        String viewId = resolver.get().getDefaultViewId();
        for (StoredZone zone : zones.scan(k -> true)) {
            if (viewId.equals(zone.getViewId())) {
                zones.delete(zone.getId());
            }
        }
        for (StoredResolver other : resolvers.scan(k -> true)) {
            if (!other.getId().equals(resolver.get().getId()) && other.getAttachedViewIds().contains(viewId)) {
                List<String> attached = new ArrayList<>(other.getAttachedViewIds());
                attached.remove(viewId);
                other.setAttachedViewIds(attached);
                other.setTimeUpdated(Instant.now().toString());
                other.setEtag(Etags.newEtag());
                resolvers.put(other.getId(), other);
                LOG.infov("Detached deleted view {0} from resolver {1}", viewId, other.getId());
            }
        }
        views.delete(viewId);
        resolvers.delete(resolver.get().getId());
        LOG.infov("Deleted resolver {0} of VCN {1}", resolver.get().getId(), vcnId);
    }

    private Optional<StoredResolver> resolverForVcn(String vcnId) {
        return resolvers.scan(k -> true).stream().filter(r -> vcnId.equals(r.getAttachedVcnId())).findFirst();
    }

    public StoredResolver getResolver(String resolverId) {
        return resolvers.get(resolverId).orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: resolver " + resolverId));
    }

    public List<StoredResolver> listResolvers(String compartmentId, String displayName, String id,
                                              String lifecycleState, String sortBy, String sortOrder) {
        Comparator<StoredResolver> order = switch (sortBy == null ? "timeCreated" : sortBy) {
            case "displayName" -> Comparator.comparing(r -> r.getDisplayName().toLowerCase(Locale.ROOT));
            case "timeCreated" -> Comparator.comparing(StoredResolver::getTimeCreated);
            default -> throw OciException.invalidParameter("sortBy must be displayName or timeCreated");
        };
        return resolvers.scan(k -> true).stream()
                .filter(r -> compartmentId.equals(r.getCompartmentId()))
                .filter(r -> displayName == null || displayName.equals(r.getDisplayName()))
                .filter(r -> id == null || id.equals(r.getId()))
                .filter(r -> lifecycleState == null || lifecycleState.equals(r.getLifecycleState()))
                .sorted("DESC".equals(sortOrder) ? order.reversed() : order)
                .toList();
    }

    public synchronized StoredResolver updateResolver(String resolverId, Map<String, Object> body, String ifMatch) {
        StoredResolver resolver = getResolver(resolverId);
        Etags.checkIfMatch(ifMatch, resolver.getEtag());
        Map<String, Object> b = body == null ? Map.of() : body;
        List<String> attached = null;
        if (b.get("attachedViews") instanceof List<?> given) {
            attached = new ArrayList<>();
            for (Map<String, Object> item : maps(given)) {
                String viewId = str(item, "viewId");
                if (viewId == null) {
                    throw OciException.missingParameter("Every attached view needs a viewId");
                }
                requireView(viewId);
                if (!attached.contains(viewId)) {
                    attached.add(viewId);
                }
            }
        }
        List<Map<String, Object>> rules = b.get("rules") instanceof List<?> given ? forwardRules(given) : null;
        if (str(b, "displayName") != null) {
            resolver.setDisplayName(str(b, "displayName"));
        }
        if (attached != null) {
            resolver.setAttachedViewIds(attached);
        }
        if (rules != null) {
            resolver.setRules(rules);
        }
        if (b.get("freeformTags") instanceof Map<?, ?>) {
            resolver.setFreeformTags(tags(b.get("freeformTags")));
        }
        if (b.get("definedTags") instanceof Map<?, ?>) {
            resolver.setDefinedTags(tags(b.get("definedTags")));
        }
        resolver.setTimeUpdated(Instant.now().toString());
        resolver.setEtag(Etags.newEtag());
        resolvers.put(resolverId, resolver);
        return resolver;
    }

    /** ResolverForwardRuleDetails, the one rule action (dns/resolver_forward_rule_details.go). */
    private static List<Map<String, Object>> forwardRules(List<?> given) {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (Map<String, Object> item : maps(given)) {
            String action = str(item, "action");
            if (action == null) {
                throw OciException.missingParameter("Every resolver rule needs an action");
            }
            if (!"FORWARD".equals(action)) {
                throw OciException.invalidParameter("Resolver rule action must be FORWARD, got " + action);
            }
            if (!(item.get("destinationAddresses") instanceof List<?> destinations) || destinations.isEmpty()) {
                throw OciException.missingParameter("A FORWARD rule needs destinationAddresses");
            }
            String sourceEndpointName = str(item, "sourceEndpointName");
            if (sourceEndpointName == null) {
                throw OciException.missingParameter("A FORWARD rule needs a sourceEndpointName");
            }
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("action", "FORWARD");
            rule.put("clientAddressConditions", item.get("clientAddressConditions") instanceof List<?> l ? l : List.of());
            rule.put("qnameCoverConditions", item.get("qnameCoverConditions") instanceof List<?> l ? l : List.of());
            rule.put("destinationAddresses", destinations);
            rule.put("sourceEndpointName", sourceEndpointName);
            rules.add(rule);
        }
        return rules;
    }

    public synchronized void changeResolverCompartment(String resolverId, String compartmentId, String ifMatch) {
        StoredResolver resolver = getResolver(resolverId);
        Etags.checkIfMatch(ifMatch, resolver.getEtag());
        if (compartmentId == null) {
            throw OciException.missingParameter("compartmentId is required");
        }
        resolver.setCompartmentId(compartmentId);
        resolver.setTimeUpdated(Instant.now().toString());
        resolver.setEtag(Etags.newEtag());
        resolvers.put(resolverId, resolver);
        views.get(resolver.getDefaultViewId()).ifPresent(view -> {
            view.setCompartmentId(compartmentId);
            touch(view);
            views.put(view.getId(), view);
        });
    }

    // ── Resolution support ─────────────────────────────────────────────────────

    /** Every ACTIVE zone of every tenancy and region, for the embedded DNS server. */
    public List<StoredZone> allActiveZones() {
        return TenancyAwareStorageBackend.scanAll(zones).stream()
                .filter(z -> "ACTIVE".equals(z.getLifecycleState()))
                .toList();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static OciException zoneNotFound(String zoneNameOrId) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: zone " + zoneNameOrId);
    }

    private static String enumValue(String value, Set<String> allowed, String field, String fallback) {
        if (value == null) {
            return fallback;
        }
        if (!allowed.contains(value)) {
            throw OciException.invalidParameter(field + " must be one of " + allowed.stream().sorted().toList()
                    + ", got " + value);
        }
        return value;
    }

    private static Integer ttl(Object value, boolean required) {
        if (value == null) {
            if (required) {
                throw OciException.missingParameter("ttl is required");
            }
            return null;
        }
        if (!(value instanceof Number number) || number.longValue() < 0 || number.longValue() > Integer.MAX_VALUE) {
            throw OciException.invalidParameter("ttl must be a non-negative integer, got " + value);
        }
        return number.intValue();
    }

    private static Instant time(String value, String field) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw OciException.invalidParameter(field + " must be an RFC 3339 timestamp, got " + value);
        }
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    static String trimDot(String name) {
        String trimmed = name.trim();
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object items) {
        if (items == null) {
            return List.of();
        }
        if (!(items instanceof List<?> list)) {
            throw OciException.invalidParameter("items must be a list");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw OciException.invalidParameter("Every item must be an object");
            }
            result.add((Map<String, Object>) map);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tags(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    static String str(Map<String, Object> b, String key) {
        return b != null && b.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return Stream.concat(
                CompartmentUsage.live(zones, tenancyId, compartmentId, StoredZone::getCompartmentId,
                        StoredZone::getLifecycleState, z -> "dns zone " + z.getId()),
                Stream.concat(
                        CompartmentUsage.live(views, tenancyId, compartmentId, StoredView::getCompartmentId,
                                StoredView::getLifecycleState, v -> "dns view " + v.getId()),
                        CompartmentUsage.live(resolvers, tenancyId, compartmentId, StoredResolver::getCompartmentId,
                                StoredResolver::getLifecycleState, r -> "dns resolver " + r.getId()))).toList();
    }
}
