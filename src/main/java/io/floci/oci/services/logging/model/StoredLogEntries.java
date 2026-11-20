package io.floci.oci.services.logging.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The entries ingested into one log, oldest first. Each entry is the stored envelope
 * (Logging/Reference/top_level_logging_format.htm) plus {@code datetime} in epoch milliseconds.
 */
@RegisterForReflection
public class StoredLogEntries {

    private String logId;
    private List<Map<String, Object>> entries = new ArrayList<>();

    public StoredLogEntries() {
    }

    public String getLogId() {
        return logId;
    }

    public void setLogId(String logId) {
        this.logId = logId;
    }

    public List<Map<String, Object>> getEntries() {
        return entries;
    }

    public void setEntries(List<Map<String, Object>> entries) {
        this.entries = entries;
    }
}
