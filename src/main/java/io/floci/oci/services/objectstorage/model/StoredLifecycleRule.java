package io.floci.oci.services.objectstorage.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One rule of an object lifecycle policy, as the client sent it. {@code target} and the name filter are
 * kept null when the client left them out, so they read back exactly as written.
 */
@RegisterForReflection
public class StoredLifecycleRule {

    private String name;
    private String action;
    private long timeAmount;
    private String timeUnit;
    private boolean enabled;
    private String target;
    private StoredObjectNameFilter objectNameFilter;

    public StoredLifecycleRule() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public long getTimeAmount() {
        return timeAmount;
    }

    public void setTimeAmount(long timeAmount) {
        this.timeAmount = timeAmount;
    }

    public String getTimeUnit() {
        return timeUnit;
    }

    public void setTimeUnit(String timeUnit) {
        this.timeUnit = timeUnit;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public StoredObjectNameFilter getObjectNameFilter() {
        return objectNameFilter;
    }

    public void setObjectNameFilter(StoredObjectNameFilter objectNameFilter) {
        this.objectNameFilter = objectNameFilter;
    }
}
