package io.floci.oci.services.monitoring.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A metric stream: one metric with one set of dimension values, and its raw data points
 * in arrival order.
 */
@RegisterForReflection
public class StoredMetricStream {

    private String namespace;
    private String compartmentId;
    private String name;
    private String resourceGroup;
    private Map<String, String> dimensions = new LinkedHashMap<>();
    private Map<String, String> metadata = new LinkedHashMap<>();
    private List<StoredDatapoint> datapoints = new ArrayList<>();

    public StoredMetricStream() {
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getResourceGroup() {
        return resourceGroup;
    }

    public void setResourceGroup(String resourceGroup) {
        this.resourceGroup = resourceGroup;
    }

    public Map<String, String> getDimensions() {
        return dimensions;
    }

    public void setDimensions(Map<String, String> dimensions) {
        this.dimensions = dimensions;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public List<StoredDatapoint> getDatapoints() {
        return datapoints;
    }

    public void setDatapoints(List<StoredDatapoint> datapoints) {
        this.datapoints = datapoints;
    }
}
