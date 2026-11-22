package io.floci.oci.services.monitoring.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One raw data point as PostMetricData received it; {@code count} defaults to 1.
 */
@RegisterForReflection
public class StoredDatapoint {

    private long timestamp;
    private double value;
    private int count = 1;

    public StoredDatapoint() {
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }
}
