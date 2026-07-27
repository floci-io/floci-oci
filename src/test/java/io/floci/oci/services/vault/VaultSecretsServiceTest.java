package io.floci.oci.services.vault;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.services.vault.model.StoredVaultSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class VaultSecretsServiceTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..vaulttestcompartment";
    private static final String VAULT = "ocid1.vault.oc1.iad.testvault";
    private static final String KEY = "ocid1.key.oc1.iad.testkey";

    private VaultSecretsService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        service = new VaultSecretsService(new InMemoryStorage<>(), config);
    }

    private static Map<String, Object> content(String value) {
        return Map.of("contentType", "BASE64",
                "content", Base64.getEncoder().encodeToString(value.getBytes()));
    }

    private StoredVaultSecret create(String name, String value) {
        return service.createSecret(COMPARTMENT, VAULT, KEY, name, "d", content(value),
                null, null, null);
    }

    @Test
    void createRequiresTheFourMandatoryFields() {
        assertThrows(OciException.class, () -> service.createSecret(null, VAULT, KEY, "n", null,
                content("x"), null, null, null));
        assertThrows(OciException.class, () -> service.createSecret(COMPARTMENT, null, KEY, "n", null,
                content("x"), null, null, null));
        assertThrows(OciException.class, () -> service.createSecret(COMPARTMENT, VAULT, null, "n", null,
                content("x"), null, null, null));
        assertThrows(OciException.class, () -> service.createSecret(COMPARTMENT, VAULT, KEY, null, null,
                content("x"), null, null, null));
    }

    @Test
    void nonBase64ContentTypeIsRejected() {
        OciException e = assertThrows(OciException.class,
                () -> service.createSecret(COMPARTMENT, VAULT, KEY, "bad", null,
                        Map.of("contentType", "TEXT", "content", "x"), null, null, null));
        assertEquals("InvalidParameter", e.getCode());
    }

    @Test
    void secretVersioningAndStages() {
        StoredVaultSecret secret = create("db-password", "v1");
        assertEquals(1, secret.getCurrentVersionNumber());
        assertEquals(List.of("CURRENT", "LATEST"), secret.getVersions().get(0).getStages());

        service.updateSecret(secret.getId(), null, content("v2"), null, null, null, null);
        StoredVaultSecret updated = service.getSecret(secret.getId());
        assertEquals(2, updated.getCurrentVersionNumber());
        assertTrue(updated.getVersions().get(0).getStages().contains("PREVIOUS"));
        assertTrue(updated.getVersions().get(1).getStages().contains("CURRENT"));
    }

    @Test
    void bundleRetrievalByIdStageAndVersion() {
        StoredVaultSecret secret = create("api-key", "first");
        service.updateSecret(secret.getId(), null, content("second"), null, null, null, null);

        VaultSecretsService.Bundle current = service.getBundle(secret.getId(), null, null);
        assertEquals("second", new String(Base64.getDecoder().decode(current.version().getContent())));
        assertEquals(2, current.version().getVersionNumber());

        VaultSecretsService.Bundle previous = service.getBundle(secret.getId(), null, "PREVIOUS");
        assertEquals("first", new String(Base64.getDecoder().decode(previous.version().getContent())));

        VaultSecretsService.Bundle v1 = service.getBundle(secret.getId(), 1L, null);
        assertEquals(1, v1.version().getVersionNumber());
    }

    @Test
    void bundleByNameResolvesWithinVault() {
        create("named-secret", "payload");
        VaultSecretsService.Bundle bundle =
                service.getBundleByName("named-secret", VAULT, null, null);
        assertEquals("payload", new String(Base64.getDecoder().decode(bundle.version().getContent())));
        assertThrows(OciException.class,
                () -> service.getBundleByName("named-secret", "ocid1.vault.oc1.iad.other", null, null));
    }

    @Test
    void scheduledDeletionHidesBundles() {
        StoredVaultSecret secret = create("doomed", "gone");
        StoredVaultSecret pending = service.scheduleSecretDeletion(secret.getId(), null, null);
        assertEquals("PENDING_DELETION", pending.getLifecycleState());
        assertNotNull(pending.getTimeOfDeletion());
        assertThrows(OciException.class, () -> service.getBundle(secret.getId(), null, null));

        service.cancelSecretDeletion(secret.getId(), null);
        assertEquals("gone", new String(Base64.getDecoder().decode(
                service.getBundle(secret.getId(), null, null).version().getContent())));
    }

    @Test
    void duplicateNameInVaultIsConflict() {
        create("dup", "a");
        OciException e = assertThrows(OciException.class, () -> create("dup", "b"));
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void listFiltersByVaultAndName() {
        create("s1", "x");
        create("s2", "y");
        assertEquals(2, service.listSecrets(COMPARTMENT, VAULT, null).size());
        assertEquals(1, service.listSecrets(COMPARTMENT, VAULT, "s1").size());
        assertEquals(0, service.listSecrets(COMPARTMENT, "ocid1.vault.oc1.iad.other", null).size());
    }
}
