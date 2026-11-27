package io.floci.oci.services.networking.model;

import io.floci.oci.services.networking.NetworkKind;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Core Virtual Network resource. The fields every type shares are typed; the type-specific
 * wire fields ({@code cidrBlocks}, {@code routeRules}, {@code ingressSecurityRules},
 * {@code options}, ...) live in {@link #attributes}, already normalized to their OCI wire shape,
 * because the rule and option objects are polymorphic JSON that the service validates and echoes
 * rather than interprets.
 */
@RegisterForReflection
public class StoredNetworkResource {

    private String id;
    private NetworkKind kind;
    private String compartmentId;
    private String vcnId;
    private String displayName;
    private String lifecycleState;
    private String timeCreated;
    private String etag;
    private boolean vcnDefault;
    private boolean addressFromWorkload;
    private String resourceId;
    private Map<String, String> freeformTags = new LinkedHashMap<>();
    private Map<String, Map<String, Object>> definedTags = new LinkedHashMap<>();
    private Map<String, Object> attributes = new LinkedHashMap<>();
    private List<Map<String, Object>> securityRules = new ArrayList<>();

    public StoredNetworkResource() {
    }

    /** A deep copy, so an update can be validated in full before anything stored changes. */
    public StoredNetworkResource copy() {
        StoredNetworkResource copy = new StoredNetworkResource();
        copy.id = id;
        copy.kind = kind;
        copy.compartmentId = compartmentId;
        copy.vcnId = vcnId;
        copy.displayName = displayName;
        copy.lifecycleState = lifecycleState;
        copy.timeCreated = timeCreated;
        copy.etag = etag;
        copy.vcnDefault = vcnDefault;
        copy.addressFromWorkload = addressFromWorkload;
        copy.resourceId = resourceId;
        copy.freeformTags = new LinkedHashMap<>(freeformTags);
        copy.definedTags = deepCopyMap(definedTags);
        copy.attributes = deepCopyMap(attributes);
        copy.securityRules = new ArrayList<>();
        for (Map<String, Object> rule : securityRules) {
            copy.securityRules.add(deepCopyMap(rule));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> deepCopyMap(Map<String, V> source) {
        Map<String, V> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, (V) deepCopy(value)));
        return copy;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(key, deepCopy(nested)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object nested : list) {
                copy.add(deepCopy(nested));
            }
            return copy;
        }
        return value;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public NetworkKind getKind() {
        return kind;
    }

    public void setKind(NetworkKind kind) {
        this.kind = kind;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    /** The owning VCN; null for a VCN itself. */
    public String getVcnId() {
        return vcnId;
    }

    public void setVcnId(String vcnId) {
        this.vcnId = vcnId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
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

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    /** True for the route table, security list and DHCP options CreateVcn makes; those cannot be deleted. */
    public boolean isVcnDefault() {
        return vcnDefault;
    }

    public void setVcnDefault(boolean vcnDefault) {
        this.vcnDefault = vcnDefault;
    }

    /**
     * True for a VNIC whose private IP is the one its workload reports (a container instance on
     * Docker), which may change when the workload restarts; false for a requested or allocated one.
     */
    public boolean isAddressFromWorkload() {
        return addressFromWorkload;
    }

    public void setAddressFromWorkload(boolean addressFromWorkload) {
        this.addressFromWorkload = addressFromWorkload;
    }

    /**
     * For a VNIC, the OCID of the resource it is attached to (a container instance), which
     * ListNetworkSecurityGroupVnics returns as {@code resourceId}. Not part of the Vnic model.
     */
    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
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

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    /** Network security group rules, managed through the add/update/remove actions. */
    public List<Map<String, Object>> getSecurityRules() {
        return securityRules;
    }

    public void setSecurityRules(List<Map<String, Object>> securityRules) {
        this.securityRules = securityRules;
    }
}
