package io.floci.oci.core.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SignatureAuthFilterTest {

    @Test
    void emulatorEndpointsAndParsAreExemptWithTheirLeadingSlash() {
        assertTrue(SignatureAuthFilter.isExempt("/health"));
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
        assertFalse(SignatureAuthFilter.isExempt("/healthz"));
        assertFalse(SignatureAuthFilter.isExempt("/p"));
    }
}
