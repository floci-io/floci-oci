package io.floci.oci.core.common;

import io.floci.oci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * The tenancy, region and realm a request runs in, falling back to the configured defaults
 * outside a request scope (startup, unit tests).
 *
 * <p>The realm follows the region: a region in the SDK table carries its own realm, so
 * {@code uk-gov-london-1} mints {@code oc4} OCIDs whatever {@code default-realm} says.
 * {@code default-realm} only applies to regions the table does not know.
 */
@ApplicationScoped
public class OciContext {

    private final EmulatorConfig config;
    private final Instance<RequestContext> requestContext;

    @Inject
    public OciContext(EmulatorConfig config, Instance<RequestContext> requestContext) {
        this.config = config;
        this.requestContext = requestContext;
    }

    /** A context that always answers with the configured defaults. */
    public static OciContext fromConfig(EmulatorConfig config) {
        return new OciContext(config, null);
    }

    public String tenancyId() {
        return RequestContext.currentTenancyId(requestContext, config.defaultTenancyId());
    }

    /** The region the request addresses, e.g. {@code us-ashburn-1}. */
    public String region() {
        if (requestContext != null) {
            try {
                String region = requestContext.get().getRegion();
                if (region != null) {
                    return region;
                }
            } catch (ContextNotActiveException ignored) {
                // Outside request scope: fall through to the default.
            }
        }
        return config.defaultRegion();
    }

    /** The region segment of regional OCIDs, e.g. {@code iad}. */
    public String regionCode() {
        return Regions.code(region());
    }

    public String realm() {
        return realmOf(region());
    }

    /**
     * The region a regional OCID was minted in, read from its region segment
     * ({@code ocid1.cluster.oc1.phx.<unique>} is {@code us-phoenix-1}). Global OCIDs, and codes
     * outside the region table (the fallback code of a custom default region), answer the
     * configured default region.
     */
    public String regionOf(String ocid) {
        return Ocids.parse(ocid)
                .map(Ocids.Ocid::region)
                .flatMap(Regions::byCode)
                .map(Regions.Region::name)
                .orElseGet(() -> config.defaultRegion());
    }

    /** The tenancy's home region: always the configured default region. */
    public String homeRegion() {
        return config.defaultRegion();
    }

    public String realmOf(String region) {
        return Regions.byName(region).map(Regions.Region::realm).orElse(config.defaultRealm());
    }
}
