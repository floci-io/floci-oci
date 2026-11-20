package io.floci.oci.services.logging.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A log group, log or saved search (oci-go-sdk logging/log_group.go, log.go,
 * log_saved_search.go). {@code spec} holds the client-settable fields in wire casing; the rest are
 * the fields the service owns. {@code logGroupId} is set on logs only.
 */
@RegisterForReflection
public class StoredLoggingResource {

    private String kind;
    private String id;
    private String compartmentId;
    private String logGroupId;
    private String lifecycleState;
    private String timeCreated;
    private String timeLastModified;
    private String etag;
    private Map<String, Object> spec = new LinkedHashMap<>();

    public StoredLoggingResource() {
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    public String getLogGroupId() {
        return logGroupId;
    }

    public void setLogGroupId(String logGroupId) {
        this.logGroupId = logGroupId;
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

    public String getTimeLastModified() {
        return timeLastModified;
    }

    public void setTimeLastModified(String timeLastModified) {
        this.timeLastModified = timeLastModified;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public Map<String, Object> getSpec() {
        return spec;
    }

    public void setSpec(Map<String, Object> spec) {
        this.spec = spec;
    }
}
