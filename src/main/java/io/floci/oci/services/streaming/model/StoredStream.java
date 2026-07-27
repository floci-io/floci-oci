package io.floci.oci.services.streaming.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A stream with its partitioned message log and consumer-group committed offsets. */
@RegisterForReflection
public class StoredStream {

    private String id;
    private String name;
    private String compartmentId;
    private String streamPoolId;
    private int partitions;
    private int retentionInHours;
    private String lifecycleState;
    private String timeCreated;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String etag;
    /** partition index → append-only message log. */
    private Map<Integer, List<StoredEntry>> log = new HashMap<>();
    /** group name → (partition → committed offset). */
    private Map<String, Map<Integer, Long>> groupOffsets = new HashMap<>();

    public StoredStream() {
    }

    @RegisterForReflection
    public static class StoredEntry {

        private String key;
        private String value;
        private long offset;
        private String timestamp;

        public StoredEntry() {
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public long getOffset() {
            return offset;
        }

        public void setOffset(long offset) {
            this.offset = offset;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(String timestamp) {
            this.timestamp = timestamp;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    public String getStreamPoolId() {
        return streamPoolId;
    }

    public void setStreamPoolId(String streamPoolId) {
        this.streamPoolId = streamPoolId;
    }

    public int getPartitions() {
        return partitions;
    }

    public void setPartitions(int partitions) {
        this.partitions = partitions;
    }

    public int getRetentionInHours() {
        return retentionInHours;
    }

    public void setRetentionInHours(int retentionInHours) {
        this.retentionInHours = retentionInHours;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public Map<String, String> getFreeformTags() {
        return freeformTags;
    }

    public void setFreeformTags(Map<String, String> freeformTags) {
        this.freeformTags = freeformTags;
    }

    public Map<String, Map<String, Object>> getDefinedTags() {
        return definedTags;
    }

    public void setDefinedTags(Map<String, Map<String, Object>> definedTags) {
        this.definedTags = definedTags;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public Map<Integer, List<StoredEntry>> getLog() {
        return log;
    }

    public void setLog(Map<Integer, List<StoredEntry>> log) {
        this.log = log;
    }

    public List<StoredEntry> partitionLog(int partition) {
        return log.computeIfAbsent(partition, p -> new ArrayList<>());
    }

    public Map<String, Map<Integer, Long>> getGroupOffsets() {
        return groupOffsets;
    }

    public void setGroupOffsets(Map<String, Map<Integer, Long>> groupOffsets) {
        this.groupOffsets = groupOffsets;
    }
}
