package io.floci.oci.core.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkReferencesTest {

    @Test
    void idsOfAnAbsentFieldAreEmpty() {
        assertEquals(List.of(), NetworkReferences.ids(null, "nsgIds"));
    }

    @Test
    void idsOfAListOfStringsAreThoseStrings() {
        assertEquals(List.of("ocid1.a", "ocid1.b"), NetworkReferences.ids(List.of("ocid1.a", "ocid1.b"), "nsgIds"));
    }

    @Test
    void aValueThatIsNotAListIsInvalid() {
        OciException e = assertThrows(OciException.class,
                () -> NetworkReferences.ids("ocid1.networksecuritygroup.oc1.iad.x", "networkDetails.nsgIds"));
        assertEquals(400, e.getHttpStatus());
        assertEquals("InvalidParameter", e.getCode());
        assertEquals("networkDetails.nsgIds must be a list of OCIDs", e.getMessage());
        assertThrows(OciException.class, () -> NetworkReferences.ids(Map.of("id", "x"), "nsgIds"));
    }

    @Test
    void aListWithANonStringItemIsInvalid() {
        assertThrows(OciException.class, () -> NetworkReferences.ids(List.of("ocid1.a", 7), "nsgIds"));
        assertThrows(OciException.class, () -> NetworkReferences.ids(Arrays.asList("ocid1.a", null), "nsgIds"));
    }
}
