package io.floci.oci.core.tenancy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenancyNamesTest {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    @Test
    void derivedNamesAreStableAndDifferPerTenancy() {
        String first = TenancyNames.derive("ocid1.tenancy.oc1..a", ALPHABET, 12);

        assertTrue(first.matches("[a-z0-9]{12}"), first);
        assertEquals(first, TenancyNames.derive("ocid1.tenancy.oc1..a", ALPHABET, 12));
        assertNotEquals(first, TenancyNames.derive("ocid1.tenancy.oc1..b", ALPHABET, 12));
    }
}
