package io.floci.oci.services.identity;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.services.identity.model.StoredCompartment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.locks.Lock;

/**
 * Checks that a compartment referenced by another service exists and is ACTIVE. With Identity
 * disabled nothing could create a compartment, so every compartment OCID is accepted.
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

    /**
     * For a request that creates a resource in {@code compartmentId} or moves one into it. Checks
     * the compartment like {@link #requireInBody} and keeps it from being deleted until the
     * returned handle is closed, so the resource cannot land in a compartment deleted in between:
     * <pre>{@code try (CompartmentValidator.Use ignored = compartments.useInBody(id)) { create... }}</pre>
     */
    public Use useInBody(String compartmentId) {
        if (compartmentId == null || compartmentId.isBlank() || !config.services().identity().enabled()) {
            requireInBody(compartmentId);
            return () -> { };
        }
        Lock use = identity.compartmentUseLock(compartmentId);
        use.lock();
        try {
            requireInBody(compartmentId);
        } catch (RuntimeException e) {
            use.unlock();
            throw e;
        }
        return use::unlock;
    }

    /** Keeps a compartment from being deleted while open; see {@link #useInBody}. */
    @FunctionalInterface
    public interface Use extends AutoCloseable {
        @Override
        void close();
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
        if (compartmentId == null || !config.services().identity().enabled()) {
            return false;
        }
        if (compartmentId.isBlank()) {
            return true;
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
}
