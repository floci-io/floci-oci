package io.floci.oci.core.workrequest;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * A work request — OCI's async-operation record. Serialized to the wire as
 * {@code {operationType, status, id, compartmentId, resources, percentComplete,
 * timeAccepted, timeStarted, timeFinished}}.
 */
@RegisterForReflection
public class StoredWorkRequest {

    private String id;
    /** Owning service (identity, objectstorage, queue, streaming). Persisted, but excluded
     *  from wire responses — controllers serialize via {@link #toWire()}. */
    private String service;
    private String operationType;
    private String status;
    private String compartmentId;
    private Float percentComplete;
    private String timeAccepted;
    private String timeStarted;
    private String timeFinished;
    private List<Resource> resources = new ArrayList<>();

    public StoredWorkRequest() {
    }

    /**
     * The OCI wire shape: everything except the internal {@code service} partition tag.
     * Field order matches the documented response body.
     */
    public java.util.Map<String, Object> toWire() {
        java.util.Map<String, Object> wire = new java.util.LinkedHashMap<>();
        wire.put("operationType", operationType);
        wire.put("status", status);
        wire.put("id", id);
        wire.put("compartmentId", compartmentId);
        wire.put("resources", resources.stream().map(r -> {
            java.util.Map<String, Object> res = new java.util.LinkedHashMap<>();
            res.put("entityType", r.getEntityType());
            res.put("actionType", r.getActionType());
            res.put("identifier", r.getIdentifier());
            if (r.getEntityUri() != null) {
                res.put("entityUri", r.getEntityUri());
            }
            return res;
        }).toList());
        wire.put("percentComplete", percentComplete);
        wire.put("timeAccepted", timeAccepted);
        if (timeStarted != null) {
            wire.put("timeStarted", timeStarted);
        }
        if (timeFinished != null) {
            wire.put("timeFinished", timeFinished);
        }
        return wire;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    @RegisterForReflection
    public static class Resource {

        private String entityType;
        private String actionType;
        private String identifier;
        private String entityUri;

        public Resource() {
        }

        public Resource(String entityType, String actionType, String identifier, String entityUri) {
            this.entityType = entityType;
            this.actionType = actionType;
            this.identifier = identifier;
            this.entityUri = entityUri;
        }

        public String getEntityType() {
            return entityType;
        }

        public void setEntityType(String entityType) {
            this.entityType = entityType;
        }

        public String getActionType() {
            return actionType;
        }

        public void setActionType(String actionType) {
            this.actionType = actionType;
        }

        public String getIdentifier() {
            return identifier;
        }

        public void setIdentifier(String identifier) {
            this.identifier = identifier;
        }

        public String getEntityUri() {
            return entityUri;
        }

        public void setEntityUri(String entityUri) {
            this.entityUri = entityUri;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    public Float getPercentComplete() {
        return percentComplete;
    }

    public void setPercentComplete(Float percentComplete) {
        this.percentComplete = percentComplete;
    }

    public String getTimeAccepted() {
        return timeAccepted;
    }

    public void setTimeAccepted(String timeAccepted) {
        this.timeAccepted = timeAccepted;
    }

    public String getTimeStarted() {
        return timeStarted;
    }

    public void setTimeStarted(String timeStarted) {
        this.timeStarted = timeStarted;
    }

    public String getTimeFinished() {
        return timeFinished;
    }

    public void setTimeFinished(String timeFinished) {
        this.timeFinished = timeFinished;
    }

    public List<Resource> getResources() {
        return resources;
    }

    public void setResources(List<Resource> resources) {
        this.resources = resources;
    }
}
