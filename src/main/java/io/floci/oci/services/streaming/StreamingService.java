package io.floci.oci.services.streaming;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.streaming.model.StoredStream;
import io.floci.oci.services.streaming.model.StoredStream.StoredEntry;
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
import java.util.Map;
import java.util.TreeMap;

/**
 * OCI Streaming: control plane (streams; work-request driven) and data plane
 * (partitioned append-only log with opaque cursors, Kafka-like offsets).
 */
@ApplicationScoped
public class StreamingService {

    private static final Logger LOG = Logger.getLogger(StreamingService.class);

    static final String WR_SERVICE = "streaming";
    private static final int DEFAULT_RETENTION_HOURS = 24;
    private static final int MAX_VALUE_BYTES = 1024 * 1024;

    private final StorageBackend<String, StoredStream> streams;
    private final EmulatorConfig config;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;

    @Inject
    public StreamingService(StorageFactory storageFactory, EmulatorConfig config,
                            ServiceRegistry serviceRegistry, WorkRequestService workRequests) {
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.streams = storageFactory.create("streaming", "streaming-streams.json",
                new TypeReference<Map<String, StoredStream>>() {});
    }

    StreamingService(StorageBackend<String, StoredStream> streams, EmulatorConfig config,
                     WorkRequestService workRequests) {
        this.streams = streams;
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
    }

    // ── Control plane ──────────────────────────────────────────────────────────

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
        StoredStream stream = new StoredStream();
        stream.setId(Ocids.generate("stream", config.defaultRealm(),
                Ocids.regionShort(config.defaultRegion())));
        stream.setName(name);
        stream.setCompartmentId(compartmentId);
        stream.setStreamPoolId(streamPoolId != null ? streamPoolId
                : Ocids.generate("streampool", config.defaultRealm(),
                        Ocids.regionShort(config.defaultRegion())));
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
        String wr = workRequests.succeeded(WR_SERVICE, "CREATE_STREAM",
                compartmentId != null ? compartmentId : stream.getStreamPoolId(),
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
        return config.effectiveBaseUrl();
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
}
