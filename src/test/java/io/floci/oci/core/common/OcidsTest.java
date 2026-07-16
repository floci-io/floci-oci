package io.floci.oci.core.common;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcidsTest {

    @Test
    void generateGlobalHasEmptyRegionSegment() {
        String ocid = Ocids.generateGlobal("compartment", "oc1");
        assertTrue(ocid.startsWith("ocid1.compartment.oc1.."), ocid);
        assertTrue(Ocids.isValid(ocid));
        assertTrue(Ocids.isOfType(ocid, "compartment"));
    }

    @Test
    void generateRegionalCarriesRegionKey() {
        String ocid = Ocids.generate("bucket", "oc1", "iad");
        assertTrue(ocid.startsWith("ocid1.bucket.oc1.iad."), ocid);
        Optional<Ocids.Ocid> parsed = Ocids.parse(ocid);
        assertTrue(parsed.isPresent());
        assertEquals("iad", parsed.get().region());
    }

    @Test
    void parseGlobalOcid() {
        Optional<Ocids.Ocid> parsed =
                Ocids.parse("ocid1.tenancy.oc1..aaaaaaaabbbbbbbbcccccccc");
        assertTrue(parsed.isPresent());
        assertEquals("tenancy", parsed.get().resourceType());
        assertEquals("oc1", parsed.get().realm());
        assertNull(parsed.get().region());
        assertEquals("aaaaaaaabbbbbbbbcccccccc", parsed.get().uniqueId());
    }

    @Test
    void roundTripPreservesValue() {
        String original = "ocid1.user.oc1..exampleuniqueid";
        assertEquals(original, Ocids.parse(original).orElseThrow().toString());
    }

    @Test
    void rejectsMalformedValues() {
        assertFalse(Ocids.isValid(null));
        assertFalse(Ocids.isValid(""));
        assertFalse(Ocids.isValid("not-an-ocid"));
        assertFalse(Ocids.isValid("ocid2.tenancy.oc1..abc"));
        assertFalse(Ocids.isValid("ocid1.tenancy.oc1"));
        assertFalse(Ocids.isValid("ocid1..oc1..abc"));
        assertFalse(Ocids.isValid("ocid1.tenancy.oc1.."));
    }

    @Test
    void isOfTypeChecksResourceType() {
        String ocid = Ocids.generateGlobal("user", "oc1");
        assertTrue(Ocids.isOfType(ocid, "user"));
        assertFalse(Ocids.isOfType(ocid, "group"));
    }
}
