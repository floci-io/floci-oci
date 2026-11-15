package io.floci.oci.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EmulatorInfoControllerTest {

    private static final String CERT = "-----BEGIN CERTIFICATE-----\nMIIBszCCAVmgAwIBAgIUQ0==\n-----END CERTIFICATE-----";
    private static final String KEY = "-----BEGIN PRIVATE KEY-----\nMIGHAgEAMBMGByqGSM49==\n-----END PRIVATE KEY-----";

    @Test
    void certificatesOnly_keepsEveryCertificateAndDropsTheKey() {
        Optional<String> pem = EmulatorInfoController.certificatesOnly(KEY + "\n" + CERT + "\n" + CERT + "\n");

        assertTrue(pem.isPresent());
        assertFalse(pem.get().contains("PRIVATE KEY"));
        assertEquals(2, pem.get().split("BEGIN CERTIFICATE", -1).length - 1);
    }

    @Test
    void certificatesOnly_isEmptyWithoutACertificate() {
        assertTrue(EmulatorInfoController.certificatesOnly(KEY).isEmpty());
    }

    @Test
    void manifestServices_mapsHealthStatesToEnabledAndDisabled() {
        Map<String, String> services = EmulatorInfoController.manifestServices(
                Map.of("queue", "available", "identity", "running"));

        assertEquals(Map.of("identity", "enabled", "queue", "disabled"), services);
        assertEquals("identity", services.keySet().iterator().next());
    }

    @Test
    void healthBody_carriesTheRequiredFieldsFirst() {
        Map<String, Object> body = EmulatorInfoController.healthBody(Map.of("identity", "running"), "1.2.3");

        assertEquals("UP", body.get("status"));
        assertEquals("oci", body.get("cloud"));
        assertEquals("1.2.3", body.get("version"));
        assertEquals(Map.of("identity", "running"), body.get("services"));
    }
}
