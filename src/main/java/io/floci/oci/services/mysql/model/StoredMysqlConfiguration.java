package io.floci.oci.services.mysql.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A custom MySQL configuration: the options and variables a DB system of one shape runs with,
 * stored in its tenancy and region. Each option is an Option body, {@code name} and an optional
 * {@code value}. {@code etag} never reaches the wire.
 */
@RegisterForReflection
public class StoredMysqlConfiguration {

    private String id;
    private String displayName;
    private String description;
    private String compartmentId;
    private String shapeName;
    private String parentConfigurationId;
    private List<Map<String, String>> options;
    private Map<String, Object> variables;
    private Map<String, Object> initVariables;
    private String timeCreated;
    private String timeUpdated;
    private String lifecycleState;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String etag;

    /** The ConfigurationSummary body. */
    public Map<String, Object> toSummaryWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("compartmentId", compartmentId);
        wire.put("displayName", displayName);
        if (description != null) {
            wire.put("description", description);
        }
        wire.put("shapeName", shapeName);
        wire.put("type", "CUSTOM");
        wire.put("lifecycleState", lifecycleState);
        wire.put("timeCreated", timeCreated);
        wire.put("timeUpdated", timeUpdated);
        wire.put("freeformTags", freeformTags != null ? freeformTags : Map.of());
        wire.put("definedTags", definedTags != null ? definedTags : Map.of());
        wire.put("systemTags", Map.of());
        return wire;
    }

    /** The Configuration body: the summary plus the options, the variables and the parent it was made from. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = toSummaryWire();
        wire.put("options", options != null ? options : List.of());
        wire.put("variables", variables != null ? variables : Map.of());
        if (initVariables != null) {
            wire.put("initVariables", initVariables);
        }
        if (parentConfigurationId != null) {
            wire.put("parentConfigurationId", parentConfigurationId);
        }
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

    public String getShapeName() {
        return shapeName;
    }

    public void setShapeName(String shapeName) {
        this.shapeName = shapeName;
    }

    public String getParentConfigurationId() {
        return parentConfigurationId;
    }

    public void setParentConfigurationId(String parentConfigurationId) {
        this.parentConfigurationId = parentConfigurationId;
    }

    public List<Map<String, String>> getOptions() {
        return options;
    }

    public void setOptions(List<Map<String, String>> options) {
        this.options = options;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables;
    }

    public Map<String, Object> getInitVariables() {
        return initVariables;
    }

    public void setInitVariables(Map<String, Object> initVariables) {
        this.initVariables = initVariables;
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
