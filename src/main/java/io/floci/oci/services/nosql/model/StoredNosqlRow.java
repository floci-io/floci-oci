package io.floci.oci.services.nosql.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One table row, stored under {@code <tableId>|<canonical primary key>}. {@code expiresAt} is
 * epoch milliseconds, or null when the row never expires.
 */
@RegisterForReflection
public class StoredNosqlRow {

    private String tableId;
    private String primaryKey;
    private Map<String, Object> value = new LinkedHashMap<>();
    private String version;
    private Long expiresAt;
    private String timeModified;

    public StoredNosqlRow() {
    }

    public boolean expiredAt(long nowMillis) {
        return expiresAt != null && expiresAt <= nowMillis;
    }

    public String getTableId() {
        return tableId;
    }

    public void setTableId(String tableId) {
        this.tableId = tableId;
    }

    public String getPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(String primaryKey) {
        this.primaryKey = primaryKey;
    }

    public Map<String, Object> getValue() {
        return value;
    }

    public void setValue(Map<String, Object> value) {
        this.value = value;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getTimeModified() {
        return timeModified;
    }

    public void setTimeModified(String timeModified) {
        this.timeModified = timeModified;
    }
}
