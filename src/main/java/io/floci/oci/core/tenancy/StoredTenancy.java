package io.floci.oci.core.tenancy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * A tenancy the emulator knows: its home region, and the parent tenancy when it was created as
 * an Organizations child. {@code parentTenancyId} is internal and never part of a wire shape.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredTenancy {

    private String id;
    private String name;
    private String description;
    private String homeRegion;
    private String parentTenancyId;
    private String timeCreated;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getHomeRegion() {
        return homeRegion;
    }

    public void setHomeRegion(String homeRegion) {
        this.homeRegion = homeRegion;
    }

    public String getParentTenancyId() {
        return parentTenancyId;
    }

    public void setParentTenancyId(String parentTenancyId) {
        this.parentTenancyId = parentTenancyId;
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
}
