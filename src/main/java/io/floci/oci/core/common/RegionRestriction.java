package io.floci.oci.core.common;

import java.util.Optional;

/**
 * Implemented by a service that can forbid a tenancy from using a region (governance rules of
 * type {@code ALLOWED_REGIONS}), so Identity can refuse a region subscription without knowing
 * that service.
 */
public interface RegionRestriction {

    /** Why {@code tenancyId} may not use {@code regionName}, or empty when it may. */
    Optional<String> denial(String tenancyId, String regionName);
}
