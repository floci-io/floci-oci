package io.floci.oci.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OciExceptionTest {

    @Test
    void notImplementedIsTheCatalogued501() {
        OciException e = OciException.notImplemented("Creating a DB system from source X is not supported");

        assertEquals(501, e.getHttpStatus());
        assertEquals("MethodNotImplemented", e.getCode());
        assertEquals("Creating a DB system from source X is not supported", e.getMessage());
    }
}
