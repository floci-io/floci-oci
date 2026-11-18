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
    private Map<String, String> freeformTags = new LinkedHashMap<>();
    private Map<String, Map<String, Object>> definedTags = new LinkedHashMap<>();
    private Map<String, Object> attributes = new LinkedHashMap<>();
    private List<Map<String, Object>> securityRules = new ArrayList<>();

    public StoredNetworkResource() {
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
