package io.floci.oci.services.psql.model;

import io.floci.oci.services.psql.PsqlCatalog;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A custom PostgreSQL configuration: a set of setting overrides for one major version and
 * shape, stored in its tenancy and region. {@code etag} is internal and never reaches the wire.
 */
@RegisterForReflection
public class StoredConfiguration {

    private String id;
    private String displayName;
    private String description;
    private String compartmentId;
    private String timeCreated;
    private String timeUpdated;
    private String lifecycleState;
    private String dbVersion;
    private String shape;
    private Integer instanceOcpuCount;
    private Integer instanceMemorySizeInGBs;
    private Boolean isFlexible;
    private List<String> compatibleShapes;
    private String defaultConfigId;
    private Map<String, String> overrides;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String etag;

    /** The ConfigurationSummary body. */
    public Map<String, Object> toSummaryWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("displayName", displayName);
        wire.put("compartmentId", compartmentId);
        wire.put("timeCreated", timeCreated);
        if (timeUpdated != null) {
            wire.put("timeUpdated", timeUpdated);
        }
        wire.put("lifecycleState", lifecycleState);
        wire.put("dbVersion", dbVersion);
        wire.put("shape", shape);
        wire.put("instanceOcpuCount", instanceOcpuCount);
        wire.put("instanceMemorySizeInGBs", instanceMemorySizeInGBs);
        wire.put("isFlexible", isFlexible);
        wire.put("compatibleShapes", compatibleShapes);
        wire.put("defaultConfigId", defaultConfigId);
        wire.put("freeformTags", freeformTags != null ? freeformTags : Map.of());
        wire.put("definedTags", definedTags != null ? definedTags : Map.of());
        wire.put("systemTags", Map.of());
        return wire;
    }

    /** The Configuration body: the summary plus its type, description and settings. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = toSummaryWire();
        wire.put("configType", "CUSTOM");
        if (description != null) {
            wire.put("description", description);
        }
        wire.put("configurationDetails", Map.of("items",
                PsqlCatalog.configParams(overrides != null ? overrides : Map.of())));
        return wire;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getTimeUpdated() {
        return timeUpdated;
    }

    public void setTimeUpdated(String timeUpdated) {
        this.timeUpdated = timeUpdated;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public String getDbVersion() {
        return dbVersion;
    }

    public void setDbVersion(String dbVersion) {
        this.dbVersion = dbVersion;
    }

    public String getShape() {
        return shape;
    }

    public void setShape(String shape) {
        this.shape = shape;
    }

    public Integer getInstanceOcpuCount() {
        return instanceOcpuCount;
    }

    public void setInstanceOcpuCount(Integer instanceOcpuCount) {
        this.instanceOcpuCount = instanceOcpuCount;
    }

    public Integer getInstanceMemorySizeInGBs() {
        return instanceMemorySizeInGBs;
    }

    public void setInstanceMemorySizeInGBs(Integer instanceMemorySizeInGBs) {
        this.instanceMemorySizeInGBs = instanceMemorySizeInGBs;
    }

    public Boolean getIsFlexible() {
        return isFlexible;
    }

    public void setIsFlexible(Boolean isFlexible) {
        this.isFlexible = isFlexible;
    }

    public List<String> getCompatibleShapes() {
        return compatibleShapes;
    }

    public void setCompatibleShapes(List<String> compatibleShapes) {
        this.compatibleShapes = compatibleShapes;
    }

    public String getDefaultConfigId() {
        return defaultConfigId;
    }

    public void setDefaultConfigId(String defaultConfigId) {
        this.defaultConfigId = defaultConfigId;
    }

    public Map<String, String> getOverrides() {
        return overrides;
    }

    public void setOverrides(Map<String, String> overrides) {
        this.overrides = overrides;
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
}
