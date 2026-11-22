package io.floci.oci.services.objectstorage.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** A lifecycle rule's object name filter. A list the client left out stays null. */
@RegisterForReflection
public class StoredObjectNameFilter {

    private List<String> inclusionPrefixes;
    private List<String> inclusionPatterns;
    private List<String> exclusionPatterns;

    public StoredObjectNameFilter() {
    }

    public List<String> getInclusionPrefixes() {
        return inclusionPrefixes;
    }

    public void setInclusionPrefixes(List<String> inclusionPrefixes) {
        this.inclusionPrefixes = inclusionPrefixes;
    }

    public List<String> getInclusionPatterns() {
        return inclusionPatterns;
    }

    public void setInclusionPatterns(List<String> inclusionPatterns) {
        this.inclusionPatterns = inclusionPatterns;
    }

    public List<String> getExclusionPatterns() {
        return exclusionPatterns;
    }

    public void setExclusionPatterns(List<String> exclusionPatterns) {
        this.exclusionPatterns = exclusionPatterns;
    }
}
