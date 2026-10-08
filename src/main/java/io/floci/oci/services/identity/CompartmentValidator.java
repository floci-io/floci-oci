package io.floci.oci.services.identity;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.services.identity.model.StoredCompartment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Checks that a compartment referenced by another service exists and is ACTIVE, when
 * {@code floci-oci.services.identity.strict-compartments} is on and Identity is enabled.
 * Otherwise every compartment OCID is accepted, as before.
 *
 * <p>The error depends on where the OCID travels, per Oracle's error catalog: a request body
 * reference is 400 {@code RelatedResourceNotAuthorizedOrNotFound}, a URI reference is 404
 * {@code NotAuthorizedOrNotFound}.
 */
@ApplicationScoped
public class CompartmentValidator {

    private final EmulatorConfig config;
    private final IdentityService identity;

    @Inject
    public CompartmentValidator(EmulatorConfig config, IdentityService identity) {
        this.config = config;
        this.identity = identity;
    }

    /** For a {@code compartmentId} carried in the request body. */
    public void requireInBody(String compartmentId) {
        if (isUnknown(compartmentId)) {
            throw new OciException("RelatedResourceNotAuthorizedOrNotFound",
                    "Authorization failed or requested resource not found: compartment "
                            + compartmentId, 400);
        }
    }

    /** For a {@code compartmentId} carried in the query string. */
    public void requireInQuery(String compartmentId) {
        if (isUnknown(compartmentId)) {
            throw OciException.notAuthorizedOrNotFound(
                    "Authorization failed or requested resource not found: compartment "
                            + compartmentId);
        }
    }

    private boolean isUnknown(String compartmentId) {
        if (compartmentId == null || compartmentId.isBlank() || !strict()) {
            return false;
        }
        try {
            StoredCompartment c = identity.getCompartment(compartmentId);
            return !"ACTIVE".equals(c.getLifecycleState());
        } catch (OciException e) {
            if (e.getHttpStatus() == 404) {
                return true;
            }
            throw e;
        }
    }

    private boolean strict() {
        EmulatorConfig.ServicesConfig.IdentityServiceConfig identityConfig =
                config.services().identity();
        return identityConfig.enabled() && identityConfig.strictCompartments();
    }
}
