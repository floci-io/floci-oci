package io.floci.oci.core.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetworkClaimsTest {

    private static final String SUBNET = "ocid1.subnet.oc1.iad.claimstest";

    @Test
    void claimsCountUntilEachIsClosedOnce() {
        NetworkClaims.Claim first = NetworkClaims.claim(List.of(SUBNET));
        NetworkClaims.Claim second = NetworkClaims.claim(List.of(SUBNET));
        assertEquals(2, NetworkClaims.pending(SUBNET));

        first.close();
        first.close();
        assertEquals(1, NetworkClaims.pending(SUBNET));

        second.close();
        assertEquals(0, NetworkClaims.pending(SUBNET));
    }
}
