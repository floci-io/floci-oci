package io.floci.oci.services.streaming;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.Resettable;
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
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * OCI Streaming: control plane (stream pools and streams; work-request driven) and data plane
 * (partitioned append-only log with opaque cursors, Kafka offsets). Outside mock mode each pool
 * runs a Redpanda broker that is its streams' log and serves Kafka clients.
 */
@ApplicationScoped
public class StreamingService implements CompartmentUsage, Resettable {

    private static final Logger LOG = Logger.getLogger(StreamingService.class);

    static final String WR_SERVICE = "streaming";
    private static final int DEFAULT_RETENTION_HOURS = 24;
    private static final int MAX_VALUE_BYTES = 1024 * 1024;
    static final String DEFAULT_POOL_NAME = "DefaultPool";
    private static final int TIME_SCAN_CHUNK = 1000;
    private static final long POLL_SECONDS = 1;
    static final String INTERRUPTED = "The broker was interrupted by a restart of floci-oci";

    private final StorageBackend<String, StoredStream> streams;
    private final StorageBackend<String, StoredStreamPool> pools;
    private final EmulatorConfig config;
    private final OciContext ociContext;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;
    private final StreamPoolKafkaManager kafka;
    private final Clock clock;
    /** Runs every Docker step in order: broker starts, topic creates and deletes, removals. */
    private final Executor lifecycle;
    private final ScheduledExecutorService worker;

    @Inject
    public StreamingService(StorageFactory storageFactory, EmulatorConfig config,
                            ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                            OciContext ociContext, StreamPoolKafkaManager kafka) {
        this.config = config;
        this.ociContext = ociContext;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.kafka = kafka;
        this.clock = Clock.systemUTC();
        this.streams = storageFactory.create("streaming", "streaming-streams.json",
                new TypeReference<Map<String, StoredStream>>() {});
        this.pools = storageFactory.create("streaming", "streaming-pools.json",
                new TypeReference<Map<String, StoredStreamPool>>() {});
        // No thread starts until the first task is submitted, which only real mode does.
        this.worker = Executors.newSingleThreadScheduledExecutor(daemon("streaming-kafka"));
        this.lifecycle = worker;
    }

    StreamingService(StorageBackend<String, StoredStream> streams, StorageBackend<String, StoredStreamPool> pools,
                     EmulatorConfig config, WorkRequestService workRequests) {
        this(streams, pools, config, workRequests, null, Clock.systemUTC(), Runnable::run);
    }

    /**
     * Storage-injecting constructor for tests.
     *
     * @param kafka     the broker manager; null keeps every stream in floci-oci's own storage
     * @param lifecycle runs the Docker steps; tests pass {@code Runnable::run} and call {@link #tick}
     */
    StreamingService(StorageBackend<String, StoredStream> streams, StorageBackend<String, StoredStreamPool> pools,
                     EmulatorConfig config, WorkRequestService workRequests, StreamPoolKafkaManager kafka,
                     Clock clock, Executor lifecycle) {
        this.ociContext = OciContext.fromConfig(config);
        this.streams = streams;
        this.pools = pools;
        this.config = config;
        this.serviceRegistry = null;
        this.workRequests = workRequests;
        this.kafka = kafka;
        this.clock = clock;
        this.lifecycle = lifecycle;
        this.worker = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("streaming")
                .enabled(config.services().streaming().enabled())
                .storageKey("streaming")
                .resourceClasses(StreamAdminController.class, StreamDataController.class)
                .build());
        materializeMissingPools();
        if (realMode() && config.services().streaming().enabled()) {
            worker.scheduleWithFixedDelay(this::tickSafely, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
            restartPersistedPools();
        }
    }

    boolean realMode() {
        return kafka != null && !config.services().streaming().mock();
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
        LOG.infov("createStreamPool {0} ({1})", name, pool.getId());
        if (!realMode()) {
            pools.put(pool.getId(), pool);
            String wr = workRequests.succeeded(WR_SERVICE, "CREATE_STREAM_POOL", compartmentId,
                    List.of(poolResource(pool.getId(), "CREATED")));
            return new PoolChange(pool, wr);
        }
        pool.setLifecycleState("CREATING");
        String wr = workRequests.inProgress(WR_SERVICE, "CREATE_STREAM_POOL", compartmentId,
                List.of(poolResource(pool.getId(), "CREATED")));
        pool.setWorkRequestId(wr);
        pools.put(pool.getId(), pool);
        launchBroker(pool);
        return new PoolChange(pool, wr);
    }

    public StoredStreamPool getStreamPool(String streamPoolId) {
        return pools.get(streamPoolId).orElseThrow(() -> notFound("stream pool", streamPoolId));
    }

    /** The compartment of a pool, for a stream created with only its OCID; empty for an unknown pool. */
    public Optional<String> streamPoolCompartment(String streamPoolId) {
        if (streamPoolId == null) {
            return Optional.empty();
        }
        return pools.get(streamPoolId).map(StoredStreamPool::getCompartmentId);
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

    public synchronized PoolChange updateStreamPool(String streamPoolId, String name,
                                                    Map<String, Object> kafkaSettings,
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

    public synchronized String deleteStreamPool(String streamPoolId, String ifMatch) {
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
        if (realMode()) {
            finishWorkRequest(pool, "CANCELED", null);
            lifecycle.execute(() -> removeQuietly(pool));
        }
        return workRequests.succeeded(WR_SERVICE, "DELETE_STREAM_POOL", pool.getCompartmentId(),
                List.of(poolResource(streamPoolId, "DELETED")));
    }

    public synchronized String changeStreamPoolCompartment(String streamPoolId, String compartmentId,
                                                           String ifMatch) {
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
            if (!List.of("ACTIVE", "CREATING", "UPDATING").contains(pool.getLifecycleState())) {
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
                        if (realMode()) {
                            pool.setLifecycleState("CREATING");
                        }
                        pools.put(pool.getId(), pool);
                        LOG.infov("Created the DefaultPool {0} of compartment {1}", pool.getId(), compartmentId);
                        if (realMode()) {
                            launchBroker(pool);
                        }
                        return pool;
                    });
        }
    }

    // ── Streams ────────────────────────────────────────────────────────────────

    public record CreatedStream(StoredStream stream, String workRequestId) {
    }

    public synchronized CreatedStream createStream(String name, Integer partitions, Integer retentionInHours,
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
        if (realMode()) {
            requireUniqueTopic(pool, name);
        }
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
        LOG.infof("createStream %s (%s, %d partitions)", name, stream.getId(), partitions);
        // Terraform scans this WR's resources for entityType containing "stream".
        List<StoredWorkRequest.Resource> resources = List.of(WorkRequestService.resource("stream", "CREATED",
                stream.getId(), "/20180418/streams/" + stream.getId()));
        if (!realMode()) {
            stream.setLogBackend(StoredStream.LOG_STORED);
            streams.put(stream.getId(), stream);
            String wr = workRequests.succeeded(WR_SERVICE, "CREATE_STREAM", stream.getCompartmentId(), resources);
            return new CreatedStream(stream, wr);
        }
        // The topic is made once the pool's broker is up; until then the stream is CREATING.
        stream.setLogBackend(StoredStream.LOG_REDPANDA);
        stream.setLifecycleState("CREATING");
        String wr = workRequests.inProgress(WR_SERVICE, "CREATE_STREAM", stream.getCompartmentId(), resources);
        stream.setWorkRequestId(wr);
        streams.put(stream.getId(), stream);
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

    /**
     * Deletes a stream. One whose messages are a topic on its pool's broker stays DELETING, and
     * keeps its name in the pool, until the broker confirms the topic is gone, so a stream
     * created with the same name never finds the old one's messages. The record is the pending
     * deletion: it survives a restart of floci-oci and {@link #tick} retries it once the pool's
     * broker is back.
     */
    public synchronized String deleteStream(String streamId, String ifMatch) {
        StoredStream stream = getStream(streamId);
        Etags.checkIfMatch(ifMatch, stream.getEtag());
        List<StoredWorkRequest.Resource> resources = List.of(WorkRequestService.resource("stream", "DELETED",
                streamId, "/20180418/streams/" + streamId));
        if (!stream.redpandaBacked()) {
            streams.delete(streamId);
            return workRequests.succeeded(WR_SERVICE, "DELETE_STREAM", stream.getCompartmentId(), resources);
        }
        if ("DELETING".equals(stream.getLifecycleState())) {
            throw new OciException("IncorrectState", "Stream " + streamId + " is DELETING", 409);
        }
        String partition = TenancyAwareStorageBackend.regionalPartition(ociContext.tenancyId(),
                ociContext.region());
        finishStreamWorkRequest(partition, stream, "CANCELED", null);
        String wr = workRequests.inProgress(WR_SERVICE, "DELETE_STREAM", stream.getCompartmentId(), resources);
        stream.setWorkRequestId(wr);
        stream.setLifecycleState("DELETING");
        stream.setEtag(Etags.newEtag());
        streams.put(streamId, stream);
        LOG.infov("deleteStream {0}: DELETING until the topic {1} is gone", streamId, stream.getName());
        if (realMode()) {
            lifecycle.execute(() -> retireStream(partition, streamId));
        }
        return wr;
    }

    public String messagesEndpoint() {
        return ociContext.baseUrl();
    }

    // ── Data plane: produce ────────────────────────────────────────────────────

    public record PutEntryResult(Integer partition, Long offset, String timestamp,
                                 String error, String errorMessage) {
    }

    public List<PutEntryResult> putMessages(String streamId, List<Map<String, Object>> entries) {
        StoredStream stream = getStream(streamId);
        if (entries == null || entries.isEmpty()) {
            throw OciException.invalidParameter("messages must contain at least one entry");
        }
        StreamLog log = logOf(stream);
        PutEntryResult[] results = new PutEntryResult[entries.size()];
        List<StreamLog.Append> accepted = new ArrayList<>();
        List<Integer> acceptedAt = new ArrayList<>();
        int roundRobin = 0;
        for (int i = 0; i < entries.size(); i++) {
            Map<String, Object> entry = entries.get(i);
            String value = entry.get("value") instanceof String s ? s : null;
            String key = entry.get("key") instanceof String s ? s : null;
            if (value == null) {
                results[i] = new PutEntryResult(null, null, null, "InvalidParameter", "value is required");
                continue;
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(value);
            } catch (IllegalArgumentException e) {
                results[i] = new PutEntryResult(null, null, null,
                        "InvalidParameter", "value must be base64-encoded");
                continue;
            }
            if (decoded.length > MAX_VALUE_BYTES) {
                results[i] = new PutEntryResult(null, null, null, "InvalidParameter", "value exceeds 1 MiB");
                continue;
            }
            int partition = key != null
                    ? Math.floorMod(key.hashCode(), stream.getPartitions())
                    : Math.floorMod(roundRobin++, stream.getPartitions());
            accepted.add(new StreamLog.Append(partition, key, value));
            acceptedAt.add(i);
        }
        List<StreamLog.Appended> appended = accepted.isEmpty() ? List.of() : append(stream, log, accepted);
        for (int i = 0; i < appended.size(); i++) {
            StreamLog.Appended landed = appended.get(i);
            results[acceptedAt.get(i)] = landed.error() != null
                    ? new PutEntryResult(null, null, null, "InternalServerError", landed.error())
                    : new PutEntryResult(landed.partition(), landed.offset(), landed.timestamp(), null, null);
        }
        return List.of(results);
    }

    /** The stored log changes the stream record, so its appends are serialized and saved. */
    private List<StreamLog.Appended> append(StoredStream stream, StreamLog log, List<StreamLog.Append> messages) {
        if (log != StoredStreamLog.INSTANCE) {
            return log.append(stream, messages);
        }
        synchronized (this) {
            StoredStream current = getStream(stream.getId());
            List<StreamLog.Appended> appended = log.append(current, messages);
            streams.put(current.getId(), current);
            return appended;
        }
    }

    // ── Data plane: cursors + consume ──────────────────────────────────────────

    /**
     * Cursor wire value: opaque base64 of either
     * {@code P|streamId|partition|offset} or {@code G|streamId|group|p0:o0,p1:o1,...}.
     */
    public String createCursor(String streamId, String partitionValue, String type,
                               Long offset, String time) {
        StoredStream stream = getStream(streamId);
        if (partitionValue == null) {
            throw OciException.missingParameter("Missing required parameter: partition");
        }
        int partition = parsePartition(stream, partitionValue);
        StreamLog log = logOf(stream);
        long start = switch (type != null ? type : "TRIM_HORIZON") {
            case "TRIM_HORIZON" -> 0;
            case "LATEST" -> log.endOffset(stream, partition);
            case "AT_OFFSET" -> requireOffset(offset);
            case "AFTER_OFFSET" -> requireOffset(offset) + 1;
            case "AT_TIME" -> offsetAtTime(stream, log, partition, time);
            default -> throw OciException.invalidParameter("Unsupported cursor type: " + type);
        };
        return encode("P|" + streamId + "|" + partition + "|" + start);
    }

    public String createGroupCursor(String streamId, String groupName, String type, String time,
                                    boolean commitOnGet) {
        StoredStream stream = getStream(streamId);
        require(groupName, "groupName");
        StreamLog log = logOf(stream);
        Map<Integer, Long> committed = stream.getGroupOffsets().getOrDefault(groupName, Map.of());
        StringBuilder positions = new StringBuilder();
        for (int partition = 0; partition < stream.getPartitions(); partition++) {
            long start;
            if (committed.containsKey(partition)) {
                start = committed.get(partition);
            } else {
                start = switch (type != null ? type : "TRIM_HORIZON") {
                    case "TRIM_HORIZON" -> 0;
                    case "LATEST" -> log.endOffset(stream, partition);
                    case "AT_TIME" -> offsetAtTime(stream, log, partition, time);
                    default -> throw OciException.invalidParameter(
                            "Unsupported group cursor type: " + type);
                };
            }
            if (partition > 0) {
                positions.append(',');
            }
            positions.append(partition).append(':').append(start);
        }
        synchronized (this) {
            StoredStream current = getStream(streamId);
            current.getGroupOffsets().computeIfAbsent(groupName, g -> new LinkedHashMap<>());
            streams.put(streamId, current);
        }
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
        StreamLog log = logOf(stream);
        int max = limit != null && limit > 0 ? Math.min(limit, 10000) : 100;
        List<Map<String, Object>> messages = new ArrayList<>();
        String nextCursor;
        if ("P".equals(parts[0])) {
            int partition = Integer.parseInt(parts[2]);
            long position = Long.parseLong(parts[3]);
            for (StoredEntry entry : log.read(stream, partition, position, max)) {
                messages.add(messageJson(stream, partition, entry));
                position = entry.getOffset() + 1;
            }
            nextCursor = encode("P|" + streamId + "|" + partition + "|" + position);
        } else {
            Map<Integer, Long> positions = parsePositions(parts[3]);
            readRoundRobin(stream, log, positions, max, messages);
            nextCursor = encode("G|" + streamId + "|" + parts[2] + "|" + formatPositions(positions));
        }
        return new ConsumeResult(messages, nextCursor);
    }

    /**
     * Takes one message per partition per round, so no partition starves the others, and
     * advances {@code positions} past each one taken. A partition is read only when its buffer
     * runs dry, for its share of what the response still has room for, so the records buffered
     * across partitions stay near {@code max} however many partitions the stream has.
     */
    private void readRoundRobin(StoredStream stream, StreamLog log, Map<Integer, Long> positions, int max,
                                List<Map<String, Object>> messages) {
        Map<Integer, ArrayDeque<StoredEntry>> buffered = new LinkedHashMap<>();
        Set<Integer> drained = new HashSet<>();
        for (Integer partition : positions.keySet()) {
            buffered.put(partition, new ArrayDeque<>());
        }
        boolean progress = true;
        while (messages.size() < max && progress) {
            progress = false;
            for (Map.Entry<Integer, ArrayDeque<StoredEntry>> partitionEntries : buffered.entrySet()) {
                if (messages.size() >= max) {
                    break;
                }
                int partition = partitionEntries.getKey();
                ArrayDeque<StoredEntry> buffer = partitionEntries.getValue();
                if (buffer.isEmpty() && !drained.contains(partition)) {
                    int open = buffered.size() - drained.size();
                    int share = Math.ceilDiv(max - messages.size(), open);
                    List<StoredEntry> read = log.read(stream, partition, positions.get(partition), share);
                    if (read.size() < share) {
                        drained.add(partition);
                    }
                    buffer.addAll(read);
                }
                StoredEntry entry = buffer.poll();
                if (entry != null) {
                    messages.add(messageJson(stream, partition, entry));
                    positions.put(partition, entry.getOffset() + 1);
                    progress = true;
                }
            }
        }
    }

    /** Commits a group cursor's positions and returns a refreshed cursor. */
    public synchronized String consumerCommit(String streamId, String cursorValue) {
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
        StreamLog log = logOf(stream);
        Map<Integer, Long> committed = new LinkedHashMap<>();
        for (int partition = 0; partition < stream.getPartitions(); partition++) {
            long offset = switch (type != null ? type : "TRIM_HORIZON") {
                case "TRIM_HORIZON" -> 0;
                case "LATEST" -> log.endOffset(stream, partition);
                case "AT_TIME" -> offsetAtTime(stream, log, partition, time);
                default -> throw OciException.invalidParameter("Unsupported type: " + type);
            };
            committed.put(partition, offset);
        }
        synchronized (this) {
            StoredStream current = getStream(streamId);
            current.getGroupOffsets().put(groupName, committed);
            streams.put(streamId, current);
        }
    }

    /**
     * The log a stream's messages are in. A Redpanda-backed stream needs its pool's broker,
     * which mock mode does not run, and is readable only once it and its pool are ACTIVE.
     */
    private StreamLog logOf(StoredStream stream) {
        if (!stream.redpandaBacked()) {
            return StoredStreamLog.INSTANCE;
        }
        if (!realMode()) {
            throw new OciException("IncorrectState", "Stream " + stream.getId()
                    + " keeps its messages in a Kafka broker, which mock mode does not run", 409);
        }
        if (!"ACTIVE".equals(stream.getLifecycleState())) {
            throw new OciException("IncorrectState", "Stream " + stream.getId() + " is "
                    + stream.getLifecycleState(), 409);
        }
        StoredStreamPool pool = getStreamPool(stream.getStreamPoolId());
        if (!"ACTIVE".equals(pool.getLifecycleState())) {
            throw new OciException("IncorrectState", "Stream pool " + pool.getId() + " is "
                    + pool.getLifecycleState(), 409);
        }
        return new RedpandaStreamLog(kafka, pool);
    }

    // ── Kafka broker lifecycle (real mode) ─────────────────────────────────────

    /**
     * Two streams of one pool cannot share its broker's topic, which is the stream's name. OCI
     * lets them be created and then fails Kafka authentication for them; floci-oci has nowhere
     * to put the second one's messages, so it refuses it.
     */
    private void requireUniqueTopic(StoredStreamPool pool, String name) {
        boolean taken = streams.scan(k -> true).stream()
                .anyMatch(s -> pool.getId().equals(s.getStreamPoolId()) && name.equals(s.getName())
                        && s.redpandaBacked() && !"DELETED".equals(s.getLifecycleState()));
        if (taken) {
            throw OciException.conflict("Stream pool " + pool.getId() + " already has a stream named " + name
                    + ", or one that is still DELETING");
        }
    }

    /** Queues the start of the pool's broker; {@link #tick} takes over once it is running. */
    private void launchBroker(StoredStreamPool pool) {
        String partition = TenancyAwareStorageBackend.regionalPartition(pool.getTenancyId(), pool.getRegion());
        String id = pool.getId();
        lifecycle.execute(() -> startBroker(partition, id));
    }

    private void startBroker(String partition, String id) {
        Optional<StoredStreamPool> found = TenancyAwareStorageBackend.getIn(pools, partition, id);
        if (found.isEmpty() || found.get().getBrokerStartedAt() != null) {
            return;
        }
        StoredStreamPool pool = found.get();
        try {
            kafka.stop(pool);
            kafka.start(pool);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not start the broker of stream pool {0}", id);
            synchronized (this) {
                TenancyAwareStorageBackend.getIn(pools, partition, id).ifPresent(current ->
                        failPool(partition, current, "The Kafka broker could not be started: " + e.getMessage()));
            }
            return;
        }
        synchronized (this) {
            Optional<StoredStreamPool> current = TenancyAwareStorageBackend.getIn(pools, partition, id);
            if (current.isEmpty()) {
                // Deleted or reset while the broker started: remove what the start made.
                removeQuietly(pool);
                return;
            }
            current.get().setKafkaHostPort(pool.getKafkaHostPort());
            current.get().setProxyEndpoint(pool.getProxyEndpoint());
            current.get().setBrokerStartedAt(pool.getBrokerStartedAt());
            TenancyAwareStorageBackend.putIn(pools, partition, id, current.get());
        }
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.warnv(e, "Streaming broker poll failed");
        }
    }

    /**
     * One pass over what is waiting on a broker. A pool whose broker answers becomes ACTIVE and
     * gets its {@code bootstrapServers}; one whose container exited, or that passed the ready
     * timeout, FAILED. A CREATING stream gets its topic once its pool is ACTIVE, and fails with
     * its pool. Each outcome also finishes the work request Terraform and the CLI wait on.
     */
    void tick() {
        String solePartition = TenancyAwareStorageBackend.regionalPartition(ociContext.tenancyId(),
                ociContext.region());
        for (Map.Entry<String, Map<String, StoredStreamPool>> entry
                : TenancyAwareStorageBackend.entriesByPartition(pools, solePartition).entrySet()) {
            for (StoredStreamPool pool : entry.getValue().values()) {
                if (List.of("CREATING", "UPDATING").contains(pool.getLifecycleState())
                        && pool.getBrokerStartedAt() != null) {
                    pollPool(entry.getKey(), pool);
                }
            }
        }
        for (Map.Entry<String, Map<String, StoredStream>> entry
                : TenancyAwareStorageBackend.entriesByPartition(streams, solePartition).entrySet()) {
            for (StoredStream stream : entry.getValue().values()) {
                if (!stream.redpandaBacked()) {
                    continue;
                }
                if ("DELETING".equals(stream.getLifecycleState())) {
                    retireStream(entry.getKey(), stream.getId());
                } else if ("CREATING".equals(stream.getLifecycleState())) {
                    provisionTopic(entry.getKey(), stream.getId());
                }
            }
        }
    }

    /**
     * Finishes the deletion of a DELETING stream once its topic is gone. A pool whose broker is
     * starting is waited for; a FAILED or deleted pool never runs its broker again, so nothing
     * can reach the topic and the deletion finishes without it.
     */
    void retireStream(String partition, String streamId) {
        Optional<StoredStream> found = TenancyAwareStorageBackend.getIn(streams, partition, streamId)
                .filter(stream -> "DELETING".equals(stream.getLifecycleState()));
        if (found.isEmpty()) {
            return;
        }
        StoredStream stream = found.get();
        Optional<StoredStreamPool> pool = TenancyAwareStorageBackend.getIn(pools, partition, stream.getStreamPoolId());
        String poolState = pool.map(StoredStreamPool::getLifecycleState).orElse("DELETED");
        if (List.of("CREATING", "UPDATING").contains(poolState)) {
            return;
        }
        if ("ACTIVE".equals(poolState) && !deleteTopicQuietly(pool.get(), stream.getName())) {
            return;
        }
        synchronized (this) {
            TenancyAwareStorageBackend.getIn(streams, partition, streamId)
                    .filter(current -> "DELETING".equals(current.getLifecycleState()))
                    .ifPresent(current -> {
                        finishStreamWorkRequest(partition, current, "SUCCEEDED", null);
                        TenancyAwareStorageBackend.deleteAllIn(streams, partition, List.of(streamId));
                        LOG.infov("Stream {0} is deleted", streamId);
                    });
        }
    }

    private void pollPool(String partition, StoredStreamPool pool) {
        int timeout = config.services().streaming().readyTimeoutSeconds();
        if (kafka.isReady(pool)) {
            synchronized (this) {
                TenancyAwareStorageBackend.getIn(pools, partition, pool.getId())
                        .filter(current -> Objects.equals(current.getBrokerStartedAt(), pool.getBrokerStartedAt()))
                        .ifPresent(current -> {
                            current.setLifecycleState("ACTIVE");
                            current.setLifecycleStateDetails(null);
                            current.setBootstrapServers(kafka.bootstrapServers(current));
                            finishWorkRequest(current, "SUCCEEDED", null);
                            TenancyAwareStorageBackend.putIn(pools, partition, current.getId(), current);
                            LOG.infov("Stream pool {0} is ACTIVE, Kafka at {1}", current.getId(),
                                    current.getBootstrapServers());
                        });
            }
        } else if (!kafka.isRunning(pool)) {
            synchronized (this) {
                TenancyAwareStorageBackend.getIn(pools, partition, pool.getId()).ifPresent(current ->
                        failPool(partition, current, "The Kafka broker exited before it was ready"));
            }
        } else if (clock.instant().isAfter(Instant.parse(pool.getBrokerStartedAt()).plusSeconds(timeout))) {
            synchronized (this) {
                TenancyAwareStorageBackend.getIn(pools, partition, pool.getId()).ifPresent(current ->
                        failPool(partition, current, "The Kafka broker was not ready after " + timeout + " seconds"));
            }
        }
    }

    /**
     * Gives a CREATING stream its topic. The stream is read again rather than taken from the
     * tick's scan, so one deleted since is left alone; its deletion is queued behind this tick
     * on the same thread and keeps its name held until the topic is gone.
     */
    private void provisionTopic(String partition, String streamId) {
        Optional<StoredStream> found = TenancyAwareStorageBackend.getIn(streams, partition, streamId)
                .filter(current -> "CREATING".equals(current.getLifecycleState()));
        if (found.isEmpty()) {
            return;
        }
        StoredStream stream = found.get();
        Optional<StoredStreamPool> pool = TenancyAwareStorageBackend.getIn(pools, partition, stream.getStreamPoolId());
        if (pool.isPresent() && List.of("CREATING", "UPDATING").contains(pool.get().getLifecycleState())) {
            return;
        }
        String failure = null;
        if (pool.isEmpty() || !"ACTIVE".equals(pool.get().getLifecycleState())) {
            failure = "Stream pool " + stream.getStreamPoolId() + " is "
                    + pool.map(StoredStreamPool::getLifecycleState).orElse("gone");
        } else {
            try {
                kafka.createTopic(pool.get(), stream.getName(), stream.getPartitions(), stream.getRetentionInHours());
            } catch (RuntimeException e) {
                LOG.warnv("Could not create the topic of stream {0}: {1}", stream.getId(), e.getMessage());
                failure = e.getMessage();
            }
        }
        String reason = failure;
        synchronized (this) {
            TenancyAwareStorageBackend.getIn(streams, partition, stream.getId())
                    .filter(current -> "CREATING".equals(current.getLifecycleState()))
                    .ifPresent(current -> {
                        current.setLifecycleState(reason == null ? "ACTIVE" : "FAILED");
                        finishStreamWorkRequest(partition, current, reason == null ? "SUCCEEDED" : "FAILED", reason);
                        TenancyAwareStorageBackend.putIn(streams, partition, current.getId(), current);
                    });
        }
    }

    private void failPool(String partition, StoredStreamPool pool, String message) {
        stopQuietly(pool);
        pool.setLifecycleState("FAILED");
        pool.setLifecycleStateDetails(message);
        pool.setBrokerStartedAt(null);
        finishWorkRequest(pool, "FAILED", message);
        TenancyAwareStorageBackend.putIn(pools, partition, pool.getId(), pool);
        LOG.warnv("Stream pool {0} is FAILED: {1}", pool.getId(), message);
    }

    private void finishWorkRequest(StoredStreamPool pool, String status, String error) {
        if (pool.getWorkRequestId() == null) {
            return;
        }
        workRequests.finish(pool.getTenancyId(), pool.getRegion(), pool.getWorkRequestId(), status, errors(error));
        pool.setWorkRequestId(null);
    }

    private void finishStreamWorkRequest(String partition, StoredStream stream, String status, String error) {
        if (stream.getWorkRequestId() == null) {
            return;
        }
        String region = TenancyAwareStorageBackend.regionOf(partition).orElse(ociContext.region());
        workRequests.finish(TenancyAwareStorageBackend.tenancyOf(partition), region, stream.getWorkRequestId(),
                status, errors(error));
        stream.setWorkRequestId(null);
    }

    private static List<StoredWorkRequest.ErrorEntry> errors(String error) {
        return error == null ? List.of() : List.of(new StoredWorkRequest.ErrorEntry("InternalServerError", error));
    }

    /**
     * After a restart of the emulator, starts the broker of every pool that had one again, over
     * the volume its topics are on. An ACTIVE pool is UPDATING until its broker answers, a
     * CREATING one stays CREATING; their streams wait for it.
     */
    void restartPersistedPools() {
        String solePartition = TenancyAwareStorageBackend.regionalPartition(ociContext.tenancyId(),
                ociContext.region());
        for (Map.Entry<String, Map<String, StoredStreamPool>> entry
                : TenancyAwareStorageBackend.entriesByPartition(pools, solePartition).entrySet()) {
            for (StoredStreamPool pool : entry.getValue().values()) {
                if (!List.of("ACTIVE", "CREATING", "UPDATING").contains(pool.getLifecycleState())) {
                    continue;
                }
                if ("ACTIVE".equals(pool.getLifecycleState())) {
                    pool.setLifecycleState("UPDATING");
                }
                pool.setBrokerStartedAt(null);
                pool.setTenancyId(TenancyAwareStorageBackend.tenancyOf(entry.getKey()));
                pool.setRegion(TenancyAwareStorageBackend.regionOf(entry.getKey()).orElse(pool.getRegion()));
                TenancyAwareStorageBackend.putIn(pools, entry.getKey(), pool.getId(), pool);
                launchBroker(pool);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        if (worker != null) {
            worker.shutdownNow();
        }
        if (!realMode()) {
            return;
        }
        boolean memory = "memory".equalsIgnoreCase(config.storage().mode());
        for (StoredStreamPool pool : TenancyAwareStorageBackend.scanAll(pools)) {
            if (memory) {
                removeQuietly(pool);
            } else {
                stopQuietly(pool);
            }
        }
    }

    /**
     * Removes every broker and its volume, since nothing could reach the topics once their
     * pools are forgotten; then forgets every pool and stream. A broker start still running finds
     * its pool gone and removes what it started.
     */
    @Override
    public synchronized void clear() {
        if (realMode()) {
            for (StoredStreamPool pool : TenancyAwareStorageBackend.scanAll(pools)) {
                try {
                    kafka.purge(pool);
                } catch (RuntimeException e) {
                    LOG.warnv("Could not remove the broker of stream pool {0}: {1}", pool.getId(), e.getMessage());
                }
            }
        }
        streams.clear();
        pools.clear();
    }

    private void stopQuietly(StoredStreamPool pool) {
        try {
            kafka.stop(pool);
        } catch (RuntimeException e) {
            LOG.warnv("Could not stop the broker of stream pool {0}: {1}", pool.getId(), e.getMessage());
        }
    }

    private void removeQuietly(StoredStreamPool pool) {
        try {
            kafka.remove(pool);
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove the broker of stream pool {0}: {1}", pool.getId(), e.getMessage());
        }
    }

    /** Whether the topic is gone; false leaves it for the next {@link #tick}. */
    private boolean deleteTopicQuietly(StoredStreamPool pool, String topic) {
        try {
            return kafka.deleteTopic(pool, topic);
        } catch (RuntimeException e) {
            LOG.warnv("Could not delete topic {0} of stream pool {1}: {2}", topic, pool.getId(), e.getMessage());
            return false;
        }
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
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

    /** The first offset whose message is at or after {@code time}, or the end when none is. */
    private static long offsetAtTime(StoredStream stream, StreamLog log, int partition, String time) {
        if (time == null) {
            throw OciException.invalidParameter("time is required for AT_TIME cursors");
        }
        Instant at = Instant.parse(time);
        long position = 0;
        while (true) {
            List<StoredEntry> chunk = log.read(stream, partition, position, TIME_SCAN_CHUNK);
            for (StoredEntry entry : chunk) {
                if (!Instant.parse(entry.getTimestamp()).isBefore(at)) {
                    return entry.getOffset();
                }
                position = entry.getOffset() + 1;
            }
            if (chunk.size() < TIME_SCAN_CHUNK) {
                return log.endOffset(stream, partition);
            }
        }
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
