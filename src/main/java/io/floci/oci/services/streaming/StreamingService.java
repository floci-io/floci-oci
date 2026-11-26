package io.floci.oci.services.streaming;

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
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.streaming.model.StoredStream;
import io.floci.oci.services.streaming.model.StoredStream.StoredEntry;
import io.floci.oci.services.streaming.model.StoredStreamPool;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * OCI Streaming: control plane (streams; work-request driven) and data plane
 * (partitioned append-only log with opaque cursors, Kafka-like offsets).
 */
@ApplicationScoped
public class StreamingService implements CompartmentUsage {

    private static final Logger LOG = Logger.getLogger(StreamingService.class);

    static final String WR_SERVICE = "streaming";
    private static final int DEFAULT_RETENTION_HOURS = 24;
    private static final int MAX_VALUE_BYTES = 1024 * 1024;
    static final String DEFAULT_POOL_NAME = "DefaultPool";

    private final StorageBackend<String, StoredStream> streams;
    private final StorageBackend<String, StoredStreamPool> pools;
    private final EmulatorConfig config;
    private final OciContext ociContext;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;

    @Inject
    public StreamingService(StorageFactory storageFactory, EmulatorConfig config,
                            ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                            OciContext ociContext) {
        this.config = config;
        this.ociContext = ociContext;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.streams = storageFactory.create("streaming", "streaming-streams.json",
                new TypeReference<Map<String, StoredStream>>() {});
        this.pools = storageFactory.create("streaming", "streaming-pools.json",
                new TypeReference<Map<String, StoredStreamPool>>() {});
    }

    StreamingService(StorageBackend<String, StoredStream> streams, StorageBackend<String, StoredStreamPool> pools,
                     EmulatorConfig config, WorkRequestService workRequests) {
        this.ociContext = OciContext.fromConfig(config);
        this.streams = streams;
        this.pools = pools;
        this.config = config;
        this.serviceRegistry = null;
        this.workRequests = workRequests;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("streaming")
                .enabled(config.services().streaming().enabled())
                .storageKey("streaming")
                .resourceClasses(StreamAdminController.class, StreamDataController.class)
                .build());
        materializeMissingPools();
    }

    /**
     * Gives every persisted stream whose pool has no record a {@code DefaultPool} record in the
     * stream's compartment, so pools synthesized before stream pools existed can be read, listed
     * and deleted. A stream created with only a pool OCID had no compartment: it takes the
     * tenancy's root compartment, as its pool does.
     */
    void materializeMissingPools() {
        String solePartition = TenancyAwareStorageBackend.regionalPartition(ociContext.tenancyId(),
                ociContext.region());
        int created = 0;
        for (Map.Entry<String, Map<String, StoredStream>> entry
                : TenancyAwareStorageBackend.entriesByPartition(streams, solePartition).entrySet()) {
            String partition = entry.getKey();
            String tenancyId = TenancyAwareStorageBackend.tenancyOf(partition);
            String region = TenancyAwareStorageBackend.regionOf(partition).orElse(ociContext.region());
            for (StoredStream stream : entry.getValue().values()) {
                if (stream.getCompartmentId() == null) {
                    stream.setCompartmentId(tenancyId);
                    TenancyAwareStorageBackend.putIn(streams, partition, stream.getId(), stream);
                }
                if (stream.getStreamPoolId() == null
                        || TenancyAwareStorageBackend.getIn(pools, partition, stream.getStreamPoolId()).isPresent()) {
                    continue;
                }
                StoredStreamPool pool = newPool(stream.getStreamPoolId(), DEFAULT_POOL_NAME,
                        stream.getCompartmentId(), tenancyId, region);
                pool.setDefaultPool(true);
                pool.setTimeCreated(stream.getTimeCreated());
                TenancyAwareStorageBackend.putIn(pools, partition, pool.getId(), pool);
                created++;
            }
        }
        if (created > 0) {
            LOG.infov("Created {0} stream pool records for streams that predate stream pools", created);
        }
    }

    // ── Stream pools ───────────────────────────────────────────────────────────

    public record PoolChange(StoredStreamPool pool, String workRequestId) {
    }

    public PoolChange createStreamPool(String name, String compartmentId, Map<String, Object> kafkaSettings,
                                       Map<String, Object> customEncryptionKeyDetails,
                                       Map<String, String> freeformTags,
                                       Map<String, Map<String, Object>> definedTags) {
        require(name, "name");
        require(compartmentId, "compartmentId");
        StoredStreamPool pool = newPool(Ocids.generate("streampool", ociContext.realm(), ociContext.regionCode()),
                name, compartmentId, ociContext.tenancyId(), ociContext.region());
        applyKafkaSettings(pool, kafkaSettings);
        applyEncryptionKey(pool, customEncryptionKeyDetails);
        pool.setFreeformTags(freeformTags);
        pool.setDefinedTags(definedTags);
        pools.put(pool.getId(), pool);
        LOG.infov("createStreamPool {0} ({1})", name, pool.getId());
        String wr = workRequests.succeeded(WR_SERVICE, "CREATE_STREAM_POOL", compartmentId,
                List.of(poolResource(pool.getId(), "CREATED")));
        return new PoolChange(pool, wr);
    }

    public StoredStreamPool getStreamPool(String streamPoolId) {
        return pools.get(streamPoolId).orElseThrow(() -> notFound("stream pool", streamPoolId));
    }

    public List<StoredStreamPool> listStreamPools(String compartmentId, String id, String name,
                                                  String lifecycleState, String sortBy, String sortOrder) {
        if (compartmentId == null) {
            throw OciException.missingParameter("Missing required parameter: compartmentId");
        }
        String by = sortBy != null ? sortBy.toUpperCase(Locale.ROOT) : "TIMECREATED";
        if (!List.of("NAME", "TIMECREATED").contains(by)) {
            throw OciException.invalidParameter("Unsupported sortBy: " + sortBy);
        }
        // TIMECREATED sorts descending and NAME ascending unless sortOrder says otherwise.
        boolean descending = sortOrder != null ? "DESC".equalsIgnoreCase(sortOrder) : "TIMECREATED".equals(by);
        Comparator<StoredStreamPool> order = "NAME".equals(by)
                ? Comparator.comparing(StoredStreamPool::getName)
                : Comparator.comparing(StoredStreamPool::getTimeCreated);
        return pools.scan(k -> true).stream()
                .filter(p -> compartmentId.equals(p.getCompartmentId()))
                .filter(p -> id == null || id.equals(p.getId()))
                .filter(p -> name == null || name.equals(p.getName()))
                .filter(p -> lifecycleState == null || lifecycleState.equalsIgnoreCase(p.getLifecycleState()))
                .sorted(descending ? order.reversed() : order)
                .toList();
    }

    public PoolChange updateStreamPool(String streamPoolId, String name, Map<String, Object> kafkaSettings,
                                       Map<String, Object> customEncryptionKeyDetails,
                                       Map<String, String> freeformTags,
                                       Map<String, Map<String, Object>> definedTags, String ifMatch) {
        StoredStreamPool pool = getStreamPool(streamPoolId);
        Etags.checkIfMatch(ifMatch, pool.getEtag());
        if (name != null) {
            pool.setName(name);
        }
        applyKafkaSettings(pool, kafkaSettings);
        applyEncryptionKey(pool, customEncryptionKeyDetails);
        if (freeformTags != null) {
            pool.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            pool.setDefinedTags(definedTags);
        }
        pool.setEtag(Etags.newEtag());
        pools.put(streamPoolId, pool);
        String wr = workRequests.succeeded(WR_SERVICE, "UPDATE_STREAM_POOL", pool.getCompartmentId(),
                List.of(poolResource(streamPoolId, "UPDATED")));
        return new PoolChange(pool, wr);
    }

    public String deleteStreamPool(String streamPoolId, String ifMatch) {
        StoredStreamPool pool = getStreamPool(streamPoolId);
        Etags.checkIfMatch(ifMatch, pool.getEtag());
        long held = streams.scan(k -> true).stream()
                .filter(s -> streamPoolId.equals(s.getStreamPoolId()))
                .filter(s -> !"DELETED".equals(s.getLifecycleState()))
                .count();
        if (held > 0) {
            throw new OciException("IncorrectState", "Stream pool " + streamPoolId + " still contains "
                    + held + " stream(s); delete them first", 409);
        }
        pools.delete(streamPoolId);
        LOG.infov("deleteStreamPool {0}", streamPoolId);
        return workRequests.succeeded(WR_SERVICE, "DELETE_STREAM_POOL", pool.getCompartmentId(),
                List.of(poolResource(streamPoolId, "DELETED")));
    }

    public String changeStreamPoolCompartment(String streamPoolId, String compartmentId, String ifMatch) {
        require(compartmentId, "compartmentId");
        StoredStreamPool pool = getStreamPool(streamPoolId);
        Etags.checkIfMatch(ifMatch, pool.getEtag());
        pool.setCompartmentId(compartmentId);
        pool.setEtag(Etags.newEtag());
        pools.put(streamPoolId, pool);
        return workRequests.succeeded(WR_SERVICE, "MOVE_STREAM_POOL", compartmentId,
                List.of(poolResource(streamPoolId, "MOVED")));
    }

    private StoredStreamPool newPool(String id, String name, String compartmentId, String tenancyId,
                                     String region) {
        StoredStreamPool pool = new StoredStreamPool();
        pool.setId(id);
        pool.setName(name);
        pool.setCompartmentId(compartmentId);
        pool.setTenancyId(tenancyId);
        pool.setRegion(region);
        pool.setLifecycleState("ACTIVE");
        pool.setTimeCreated(Instant.now().toString());
        pool.setAutoCreateTopicsEnable(false);
        pool.setLogRetentionHours(DEFAULT_RETENTION_HOURS);
        pool.setNumPartitions(1);
        pool.setEtag(Etags.newEtag());
        return pool;
    }

    private static void applyKafkaSettings(StoredStreamPool pool, Map<String, Object> kafkaSettings) {
        if (kafkaSettings == null) {
            return;
        }
        if (kafkaSettings.get("autoCreateTopicsEnable") instanceof Boolean b) {
            pool.setAutoCreateTopicsEnable(b);
        }
        if (kafkaSettings.get("logRetentionHours") instanceof Number n) {
            pool.setLogRetentionHours(n.intValue());
        }
        if (kafkaSettings.get("numPartitions") instanceof Number n) {
            if (n.intValue() < 1) {
                throw OciException.invalidParameter("kafkaSettings.numPartitions must be a positive integer");
            }
            pool.setNumPartitions(n.intValue());
        }
    }

    private static void applyEncryptionKey(StoredStreamPool pool, Map<String, Object> details) {
        if (details == null) {
            return;
        }
        if (!(details.get("kmsKeyId") instanceof String kmsKeyId) || kmsKeyId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: customEncryptionKeyDetails.kmsKeyId");
        }
        pool.setKmsKeyId(kmsKeyId);
    }

    private static StoredWorkRequest.Resource poolResource(String streamPoolId, String actionType) {
        // Terraform matches the lowercased entityType containing "stream_pool": "streamPool" would not.
        return WorkRequestService.resource("stream_pool", actionType, streamPoolId,
                "/20180418/streampools/" + streamPoolId);
    }

    /**
     * The pool a new stream goes into: the named one, which must exist, or else the
     * compartment's {@code DefaultPool}, made on first use.
     */
    private StoredStreamPool poolForNewStream(String compartmentId, String streamPoolId) {
        if (streamPoolId != null) {
            StoredStreamPool pool = pools.get(streamPoolId).orElseThrow(() ->
                    OciException.relatedResourceNotAuthorizedOrNotFound(
                            "Authorization failed or requested resource not found: stream pool " + streamPoolId));
            if (!"ACTIVE".equals(pool.getLifecycleState())) {
                throw new OciException("IncorrectState", "Stream pool " + streamPoolId + " is "
                        + pool.getLifecycleState(), 409);
            }
            return pool;
        }
        synchronized (this) {
            return pools.scan(k -> true).stream()
                    .filter(StoredStreamPool::isDefaultPool)
                    .filter(p -> compartmentId.equals(p.getCompartmentId()))
                    .filter(p -> !"DELETED".equals(p.getLifecycleState()))
                    .findFirst()
                    .orElseGet(() -> {
                        StoredStreamPool pool = newPool(
                                Ocids.generate("streampool", ociContext.realm(), ociContext.regionCode()),
                                DEFAULT_POOL_NAME, compartmentId, ociContext.tenancyId(), ociContext.region());
                        pool.setDefaultPool(true);
                        pools.put(pool.getId(), pool);
                        LOG.infov("Created the DefaultPool {0} of compartment {1}", pool.getId(), compartmentId);
                        return pool;
                    });
        }
    }

    // ── Streams ────────────────────────────────────────────────────────────────

    public record CreatedStream(StoredStream stream, String workRequestId) {
    }

    public CreatedStream createStream(String name, Integer partitions, Integer retentionInHours,
                                      String compartmentId, String streamPoolId,
                                      Map<String, String> freeformTags,
                                      Map<String, Map<String, Object>> definedTags) {
        require(name, "name");
        if (partitions == null || partitions < 1) {
            throw OciException.invalidParameter("partitions must be a positive integer");
        }
        if (compartmentId == null && streamPoolId == null) {
            throw OciException.missingParameter("Either compartmentId or streamPoolId is required");
        }
        StoredStreamPool pool = poolForNewStream(compartmentId, streamPoolId);
        StoredStream stream = new StoredStream();
        stream.setId(Ocids.generate("stream", ociContext.realm(),
                ociContext.regionCode()));
        stream.setName(name);
        stream.setCompartmentId(compartmentId != null ? compartmentId : pool.getCompartmentId());
        stream.setStreamPoolId(pool.getId());
        stream.setPartitions(partitions);
        stream.setRetentionInHours(retentionInHours != null ? retentionInHours : DEFAULT_RETENTION_HOURS);
        stream.setLifecycleState("ACTIVE");
        stream.setTimeCreated(Instant.now().toString());
        stream.setFreeformTags(freeformTags);
        stream.setDefinedTags(definedTags);
        stream.setEtag(Etags.newEtag());
        streams.put(stream.getId(), stream);
        LOG.infof("createStream %s (%s, %d partitions)", name, stream.getId(), partitions);
        // Terraform scans this WR's resources for entityType containing "stream".
        String wr = workRequests.succeeded(WR_SERVICE, "CREATE_STREAM", stream.getCompartmentId(),
                List.of(WorkRequestService.resource("stream", "CREATED", stream.getId(),
                        "/20180418/streams/" + stream.getId())));
        return new CreatedStream(stream, wr);
    }

    public StoredStream getStream(String streamId) {
        return streams.get(streamId).orElseThrow(() -> notFound("stream", streamId));
    }

    public List<StoredStream> listStreams(String compartmentId, String streamPoolId, String name) {
        return streams.scan(k -> true).stream()
                .filter(s -> compartmentId == null || compartmentId.equals(s.getCompartmentId()))
                .filter(s -> streamPoolId == null || streamPoolId.equals(s.getStreamPoolId()))
                .filter(s -> name == null || name.equals(s.getName()))
                .sorted(Comparator.comparing(StoredStream::getTimeCreated))
                .toList();
    }

    public record UpdatedStream(StoredStream stream, String workRequestId) {
    }

    public UpdatedStream updateStream(String streamId, Map<String, String> freeformTags,
                                      Map<String, Map<String, Object>> definedTags, String ifMatch) {
        StoredStream stream = getStream(streamId);
        Etags.checkIfMatch(ifMatch, stream.getEtag());
        if (freeformTags != null) {
            stream.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            stream.setDefinedTags(definedTags);
        }
        stream.setEtag(Etags.newEtag());
        streams.put(streamId, stream);
        String wr = workRequests.succeeded(WR_SERVICE, "UPDATE_STREAM", stream.getCompartmentId(),
                List.of(WorkRequestService.resource("stream", "UPDATED", streamId,
                        "/20180418/streams/" + streamId)));
        return new UpdatedStream(stream, wr);
    }

    public String deleteStream(String streamId, String ifMatch) {
        StoredStream stream = getStream(streamId);
        Etags.checkIfMatch(ifMatch, stream.getEtag());
        streams.delete(streamId);
        return workRequests.succeeded(WR_SERVICE, "DELETE_STREAM", stream.getCompartmentId(),
                List.of(WorkRequestService.resource("stream", "DELETED", streamId,
                        "/20180418/streams/" + streamId)));
    }

    public String messagesEndpoint() {
        return ociContext.baseUrl();
    }

    // ── Data plane: produce ────────────────────────────────────────────────────

    public record PutEntryResult(Integer partition, Long offset, String timestamp,
                                 String error, String errorMessage) {
    }

    public synchronized List<PutEntryResult> putMessages(String streamId,
                                                         List<Map<String, Object>> entries) {
        StoredStream stream = getStream(streamId);
        if (entries == null || entries.isEmpty()) {
            throw OciException.invalidParameter("messages must contain at least one entry");
        }
        List<PutEntryResult> results = new ArrayList<>();
        int roundRobin = 0;
        for (Map<String, Object> entry : entries) {
            String value = entry.get("value") instanceof String s ? s : null;
            String key = entry.get("key") instanceof String s ? s : null;
            if (value == null) {
                results.add(new PutEntryResult(null, null, null,
                        "InvalidParameter", "value is required"));
                continue;
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(value);
            } catch (IllegalArgumentException e) {
                results.add(new PutEntryResult(null, null, null,
                        "InvalidParameter", "value must be base64-encoded"));
                continue;
            }
            if (decoded.length > MAX_VALUE_BYTES) {
                results.add(new PutEntryResult(null, null, null,
                        "InvalidParameter", "value exceeds 1 MiB"));
                continue;
            }
            int partition = key != null
                    ? Math.floorMod(key.hashCode(), stream.getPartitions())
                    : Math.floorMod(roundRobin++, stream.getPartitions());
            List<StoredEntry> log = stream.partitionLog(partition);
            StoredEntry stored = new StoredEntry();
            stored.setKey(key);
            stored.setValue(value);
            stored.setOffset(log.size());
            stored.setTimestamp(Instant.now().toString());
            log.add(stored);
            results.add(new PutEntryResult(partition, stored.getOffset(), stored.getTimestamp(),
                    null, null));
        }
        streams.put(streamId, stream);
        return results;
    }

    // ── Data plane: cursors + consume ──────────────────────────────────────────

    /**
     * Cursor wire value: opaque base64 of either
     * {@code P|streamId|partition|offset} or {@code G|streamId|group|p0:o0,p1:o1,…}.
     */
    public String createCursor(String streamId, String partitionValue, String type,
                               Long offset, String time) {
        StoredStream stream = getStream(streamId);
        if (partitionValue == null) {
            throw OciException.missingParameter("Missing required parameter: partition");
        }
        int partition = parsePartition(stream, partitionValue);
        long start = switch (type != null ? type : "TRIM_HORIZON") {
            case "TRIM_HORIZON" -> 0;
            case "LATEST" -> stream.partitionLog(partition).size();
            case "AT_OFFSET" -> requireOffset(offset);
            case "AFTER_OFFSET" -> requireOffset(offset) + 1;
            case "AT_TIME" -> offsetAtTime(stream, partition, time);
            default -> throw OciException.invalidParameter("Unsupported cursor type: " + type);
        };
        return encode("P|" + streamId + "|" + partition + "|" + start);
    }

    public String createGroupCursor(String streamId, String groupName, String type, String time,
                                    boolean commitOnGet) {
        StoredStream stream = getStream(streamId);
        require(groupName, "groupName");
        Map<Integer, Long> committed = stream.getGroupOffsets()
                .computeIfAbsent(groupName, g -> new LinkedHashMap<>());
        StringBuilder positions = new StringBuilder();
        for (int partition = 0; partition < stream.getPartitions(); partition++) {
            long start;
            if (committed.containsKey(partition)) {
                start = committed.get(partition);
            } else {
                start = switch (type != null ? type : "TRIM_HORIZON") {
                    case "TRIM_HORIZON" -> 0;
                    case "LATEST" -> stream.partitionLog(partition).size();
                    case "AT_TIME" -> offsetAtTime(stream, partition, time);
                    default -> throw OciException.invalidParameter(
                            "Unsupported group cursor type: " + type);
                };
            }
            if (partition > 0) {
                positions.append(',');
            }
            positions.append(partition).append(':').append(start);
        }
        streams.put(streamId, stream);
        return encode("G|" + streamId + "|" + groupName + "|" + positions);
    }

    public record ConsumeResult(List<Map<String, Object>> messages, String nextCursor) {
    }

    public ConsumeResult getMessages(String streamId, String cursorValue, Integer limit) {
        StoredStream stream = getStream(streamId);
        String[] parts = decode(cursorValue);
        if (!parts[1].equals(streamId)) {
            throw OciException.invalidParameter("Cursor does not belong to stream " + streamId);
        }
        int max = limit != null && limit > 0 ? Math.min(limit, 10000) : 100;
        List<Map<String, Object>> messages = new ArrayList<>();
        String nextCursor;
        if ("P".equals(parts[0])) {
            int partition = Integer.parseInt(parts[2]);
            long offset = Long.parseLong(parts[3]);
            List<StoredEntry> log = stream.partitionLog(partition);
            long position = offset;
            while (position < log.size() && messages.size() < max) {
                messages.add(messageJson(stream, partition, log.get((int) position)));
                position++;
            }
            nextCursor = encode("P|" + streamId + "|" + partition + "|" + position);
        } else {
            Map<Integer, Long> positions = parsePositions(parts[3]);
            boolean progress = true;
            while (messages.size() < max && progress) {
                progress = false;
                for (Map.Entry<Integer, Long> position : positions.entrySet()) {
                    if (messages.size() >= max) {
                        break;
                    }
                    List<StoredEntry> log = stream.partitionLog(position.getKey());
                    if (position.getValue() < log.size()) {
                        messages.add(messageJson(stream, position.getKey(),
                                log.get(position.getValue().intValue())));
                        positions.put(position.getKey(), position.getValue() + 1);
                        progress = true;
                    }
                }
            }
            nextCursor = encode("G|" + streamId + "|" + parts[2] + "|" + formatPositions(positions));
        }
        return new ConsumeResult(messages, nextCursor);
    }

    /** Commits a group cursor's positions and returns a refreshed cursor. */
    public String consumerCommit(String streamId, String cursorValue) {
        StoredStream stream = getStream(streamId);
        String[] parts = decode(cursorValue);
        if ("G".equals(parts[0])) {
            Map<Integer, Long> positions = parsePositions(parts[3]);
            stream.getGroupOffsets().put(parts[2], new LinkedHashMap<>(positions));
            streams.put(streamId, stream);
        }
        return cursorValue;
    }

    public String consumerHeartbeat(String streamId, String cursorValue) {
        decode(cursorValue);
        return cursorValue;
    }

    public Map<String, Object> getGroup(String streamId, String groupName) {
        StoredStream stream = getStream(streamId);
        Map<Integer, Long> committed = stream.getGroupOffsets().getOrDefault(groupName, Map.of());
        List<Map<String, Object>> reservations = new ArrayList<>();
        new TreeMap<>(committed).forEach((partition, offset) -> {
            Map<String, Object> reservation = new LinkedHashMap<>();
            reservation.put("partition", String.valueOf(partition));
            reservation.put("committedOffset", offset);
            reservations.add(reservation);
        });
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("streamId", streamId);
        group.put("groupName", groupName);
        group.put("reservations", reservations);
        return group;
    }

    public void updateGroup(String streamId, String groupName, String type, String time) {
        StoredStream stream = getStream(streamId);
        Map<Integer, Long> committed = new LinkedHashMap<>();
        for (int partition = 0; partition < stream.getPartitions(); partition++) {
            long offset = switch (type != null ? type : "TRIM_HORIZON") {
                case "TRIM_HORIZON" -> 0;
                case "LATEST" -> stream.partitionLog(partition).size();
                case "AT_TIME" -> offsetAtTime(stream, partition, time);
                default -> throw OciException.invalidParameter("Unsupported type: " + type);
            };
            committed.put(partition, offset);
        }
        stream.getGroupOffsets().put(groupName, committed);
        streams.put(streamId, stream);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Map<String, Object> messageJson(StoredStream stream, int partition, StoredEntry entry) {
        Map<String, Object> json = new LinkedHashMap<>();
        // Message.stream is the stream NAME, not the OCID.
        json.put("stream", stream.getName());
        json.put("partition", String.valueOf(partition));
        json.put("key", entry.getKey());
        json.put("value", entry.getValue());
        json.put("offset", entry.getOffset());
        json.put("timestamp", entry.getTimestamp());
        return json;
    }

    private static int parsePartition(StoredStream stream, String partitionValue) {
        try {
            int partition = Integer.parseInt(partitionValue);
            if (partition < 0 || partition >= stream.getPartitions()) {
                throw OciException.invalidParameter("partition out of range: " + partitionValue);
            }
            return partition;
        } catch (NumberFormatException e) {
            throw OciException.invalidParameter("partition must be an integer: " + partitionValue);
        }
    }

    private static long requireOffset(Long offset) {
        if (offset == null || offset < 0) {
            throw OciException.invalidParameter("offset is required for offset-based cursors");
        }
        return offset;
    }

    private static long offsetAtTime(StoredStream stream, int partition, String time) {
        if (time == null) {
            throw OciException.invalidParameter("time is required for AT_TIME cursors");
        }
        Instant at = Instant.parse(time);
        List<StoredEntry> log = stream.partitionLog(partition);
        for (StoredEntry entry : log) {
            if (!Instant.parse(entry.getTimestamp()).isBefore(at)) {
                return entry.getOffset();
            }
        }
        return log.size();
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decode(String cursorValue) {
        if (cursorValue == null || cursorValue.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: cursor");
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursorValue), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 4);
            if (parts.length != 4 || (!"P".equals(parts[0]) && !"G".equals(parts[0]))) {
                throw new IllegalArgumentException(raw);
            }
            return parts;
        } catch (IllegalArgumentException e) {
            throw OciException.invalidParameter("Invalid cursor.");
        }
    }

    private static Map<Integer, Long> parsePositions(String positions) {
        Map<Integer, Long> parsed = new LinkedHashMap<>();
        for (String pair : positions.split(",")) {
            String[] kv = pair.split(":");
            parsed.put(Integer.parseInt(kv[0]), Long.parseLong(kv[1]));
        }
        return parsed;
    }

    private static String formatPositions(Map<Integer, Long> positions) {
        StringBuilder sb = new StringBuilder();
        positions.forEach((partition, offset) -> {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(partition).append(':').append(offset);
        });
        return sb.toString();
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: " + field);
        }
    }

    private static OciException notFound(String kind, String id) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: " + kind + " " + id);
    }

    /**
     * Streams and the pools created through CreateStreamPool. A {@code DefaultPool} is made on
     * demand and does not hold a compartment once its streams are gone.
     */
    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        Stream<String> created = CompartmentUsage.live(pools, tenancyId, compartmentId,
                p -> p.isDefaultPool() ? null : p.getCompartmentId(),
                StoredStreamPool::getLifecycleState, p -> "stream pool " + p.getId());
        return Stream.concat(CompartmentUsage.live(streams, tenancyId, compartmentId, StoredStream::getCompartmentId,
                        StoredStream::getLifecycleState, s -> "stream " + s.getId()), created).toList();
    }
}
