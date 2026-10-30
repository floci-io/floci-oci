package io.floci.oci.services.psql.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A backup of a DB system. The fields after {@code etag} are emulator internals: the owning
 * partition, the dump file it reads (a copy shares its source's) and its running work request.
 */
@RegisterForReflection
public class StoredBackup {

    private String id;
    private String displayName;
    private String description;
    private String compartmentId;
    private String timeCreated;
    private String timeUpdated;
    private String lifecycleState;
    private String lifecycleDetails;
    private String sourceType;
    private Integer backupSize;
    private Integer retentionPeriod;
    private String dbSystemId;
    private Map<String, Object> dbSystemDetails;
    private Map<String, Object> sourceBackupDetails;
    private List<Map<String, Object>> copyStatus;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String etag;
    private String tenancyId;
    private String region;
    private String dumpId;
    private String workRequestId;

    /** The BackupSummary body. */
    public Map<String, Object> toSummaryWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("displayName", displayName);
        wire.put("compartmentId", compartmentId);
        wire.put("sourceType", sourceType);
        wire.put("timeCreated", timeCreated);
        if (timeUpdated != null) {
            wire.put("timeUpdated", timeUpdated);
        }
        wire.put("backupSize", backupSize);
        wire.put("lifecycleState", lifecycleState);
        if (lifecycleDetails != null) {
            wire.put("lifecycleDetails", lifecycleDetails);
        }
        if (retentionPeriod != null) {
            wire.put("retentionPeriod", retentionPeriod);
        }
        wire.put("dbSystemId", dbSystemId);
        if (copyStatus != null && !copyStatus.isEmpty()) {
            wire.put("copyStatus", copyStatus);
        }
        wire.put("freeformTags", freeformTags != null ? freeformTags : Map.of());
        wire.put("definedTags", definedTags != null ? definedTags : Map.of());
        wire.put("systemTags", Map.of());
        return wire;
    }

    /** The Backup body: the summary plus the DB system it came from and, for a copy, its source. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = toSummaryWire();
        if (description != null) {
            wire.put("description", description);
        }
        wire.put("dbSystemDetails", dbSystemDetails);
        if (sourceBackupDetails != null) {
            wire.put("sourceBackupDetails", sourceBackupDetails);
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

    public String getLifecycleDetails() {
        return lifecycleDetails;
    }

    public void setLifecycleDetails(String lifecycleDetails) {
        this.lifecycleDetails = lifecycleDetails;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public Integer getBackupSize() {
        return backupSize;
    }

    public void setBackupSize(Integer backupSize) {
        this.backupSize = backupSize;
    }

    public Integer getRetentionPeriod() {
        return retentionPeriod;
    }

    public void setRetentionPeriod(Integer retentionPeriod) {
        this.retentionPeriod = retentionPeriod;
    }

    public String getDbSystemId() {
        return dbSystemId;
    }

    public void setDbSystemId(String dbSystemId) {
        this.dbSystemId = dbSystemId;
    }

    public Map<String, Object> getDbSystemDetails() {
        return dbSystemDetails;
    }

    public void setDbSystemDetails(Map<String, Object> dbSystemDetails) {
        this.dbSystemDetails = dbSystemDetails;
    }

    public Map<String, Object> getSourceBackupDetails() {
        return sourceBackupDetails;
    }

    public void setSourceBackupDetails(Map<String, Object> sourceBackupDetails) {
        this.sourceBackupDetails = sourceBackupDetails;
    }

    public List<Map<String, Object>> getCopyStatus() {
        return copyStatus;
    }

    public void setCopyStatus(List<Map<String, Object>> copyStatus) {
        this.copyStatus = copyStatus;
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

    public String getTenancyId() {
        return tenancyId;
    }

    public void setTenancyId(String tenancyId) {
        this.tenancyId = tenancyId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getDumpId() {
        return dumpId;
    }

    public void setDumpId(String dumpId) {
        this.dumpId = dumpId;
    }

    public String getWorkRequestId() {
        return workRequestId;
    }

    public void setWorkRequestId(String workRequestId) {
        this.workRequestId = workRequestId;
    }
}
