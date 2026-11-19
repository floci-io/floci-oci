package io.floci.oci.core.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SignatureAuthFilterTest {

    @Test
    void emulatorEndpointsAndParsAreExemptWithTheirLeadingSlash() {
        assertTrue(SignatureAuthFilter.isExempt("/health"));
        assertTrue(SignatureAuthFilter.isExempt("/_floci"));
        assertTrue(SignatureAuthFilter.isExempt("/_floci/health"));
        assertTrue(SignatureAuthFilter.isExempt("/_floci/state/reset"));
        assertTrue(SignatureAuthFilter.isExempt("/_floci-oci"));
        assertTrue(SignatureAuthFilter.isExempt("/_floci-oci/health"));
        assertTrue(SignatureAuthFilter.isExempt("/_floci-oci/state/reset"));
        assertTrue(SignatureAuthFilter.isExempt("/_floci-oci/oke/token-webhook/tenancy/cluster"));
        assertTrue(SignatureAuthFilter.isExempt("/p/token/n/ns/b/bucket/o/object"));
    }

    @Test
    void serviceApisAndLookalikePathsAreNotExempt() {
        assertFalse(SignatureAuthFilter.isExempt("/20160918/compartments"));
        assertFalse(SignatureAuthFilter.isExempt("/n/ns/b/bucket"));
        assertFalse(SignatureAuthFilter.isExempt("/_floci-ociX"));
        assertFalse(SignatureAuthFilter.isExempt("/_flociX"));
        assertFalse(SignatureAuthFilter.isExempt("/healthz"));
        assertFalse(SignatureAuthFilter.isExempt("/p"));
        assertFalse(SignatureAuthFilter.isExempt("/v2x"));
        assertFalse(SignatureAuthFilter.isExempt("/20180419/docker/tokens"));
    }

    @Test
    void registryProtocolIsExemptBecauseDockerNeverSignsOciRequests() {
        assertTrue(SignatureAuthFilter.isExempt("/v2"));
        assertTrue(SignatureAuthFilter.isExempt("/v2/"));
        assertTrue(SignatureAuthFilter.isExempt("/v2/ns/app/manifests/v1"));
        assertTrue(SignatureAuthFilter.isExempt("/20180419/docker/token"));
    }
}
