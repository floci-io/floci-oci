package io.floci.oci.services.identity;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.model.StoredApiKey;
import io.floci.oci.services.identity.model.StoredUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class ApiKeyServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..test";
    /** {@code openssl rsa -in a.pem -pubout -outform DER | openssl md5 -c} on the fixture's private key. */
    private static final String FINGERPRINT_A = "bf:44:8c:8d:6c:12:10:7b:75:d9:32:50:fc:0a:14:e5";

    private IdentityService identity;
    private ApiKeyService apiKeys;
    private StoredUser user;

    static String pem(String name) {
        try (InputStream in = ApiKeyServiceTest.class.getResourceAsStream("/identity/api-keys/" + name + ".pub")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        identity = new IdentityService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), config, new WorkRequestService(new InMemoryStorage<>(), config));
        apiKeys = new ApiKeyService(new InMemoryStorage<>(), identity, OciContext.fromConfig(config));
        user = identity.createUser("alice", "Alice", null, null, null);
    }

    @Test
    void theFingerprintIsTheMd5OfTheDerPublicKey() {
        assertEquals(FINGERPRINT_A, ApiKeyService.fingerprint(pem("a")));
    }

    @Test
    void uploadReturnsAnActiveKeyNamedByTenancyUserAndFingerprint() {
        StoredApiKey key = apiKeys.upload(user.getId(), pem("a"));

        assertEquals(TENANCY + "/" + user.getId() + "/" + FINGERPRINT_A, key.getKeyId());
        assertEquals(FINGERPRINT_A, key.getFingerprint());
        assertEquals(pem("a"), key.getKeyValue());
        assertEquals(user.getId(), key.getUserId());
        assertEquals("ACTIVE", key.getLifecycleState());
        assertNotNull(key.getTimeCreated());
        assertNotNull(key.getEtag());
        assertEquals(List.of(FINGERPRINT_A),
                apiKeys.list(user.getId()).stream().map(StoredApiKey::getFingerprint).toList());
        assertTrue(apiKeys.find(TENANCY, user.getId(), FINGERPRINT_A).isPresent());
    }

    @Test
    void aUserHoldsAtMostThreeKeys() {
        apiKeys.upload(user.getId(), pem("a"));
        apiKeys.upload(user.getId(), pem("b"));
        apiKeys.upload(user.getId(), pem("c"));

        OciException e = assertThrows(OciException.class, () -> apiKeys.upload(user.getId(), pem("d")));
        assertEquals(400, e.getHttpStatus());
        assertEquals("LimitExceeded", e.getCode());
        assertEquals(3, apiKeys.list(user.getId()).size());
    }

    @Test
    void theSameKeyCannotBeUploadedTwice() {
        apiKeys.upload(user.getId(), pem("a"));
        OciException e = assertThrows(OciException.class, () -> apiKeys.upload(user.getId(), pem("a")));
        assertEquals(409, e.getHttpStatus());
        assertEquals("Conflict", e.getCode());
    }

    @Test
    void onlyRsaPublicKeysInPemAreAccepted() {
        for (String bad : new String[] {pem("ec"), "not a key", "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n"}) {
            OciException e = assertThrows(OciException.class, () -> apiKeys.upload(user.getId(), bad), bad);
            assertEquals("InvalidParameter", e.getCode(), bad);
        }
        assertEquals("MissingParameter",
                assertThrows(OciException.class, () -> apiKeys.upload(user.getId(), " ")).getCode());
    }

    @Test
    void anUnknownUserOrFingerprintIsNotFound() {
        assertEquals("NotAuthorizedOrNotFound", assertThrows(OciException.class,
                () -> apiKeys.upload("ocid1.user.oc1..missing", pem("a"))).getCode());
        assertEquals("NotAuthorizedOrNotFound", assertThrows(OciException.class,
                () -> apiKeys.delete(user.getId(), FINGERPRINT_A, null)).getCode());
    }

    @Test
    void deleteHonoursIfMatch() {
        StoredApiKey key = apiKeys.upload(user.getId(), pem("a"));

        assertEquals("NoEtagMatch", assertThrows(OciException.class,
                () -> apiKeys.delete(user.getId(), FINGERPRINT_A, "stale")).getCode());
        apiKeys.delete(user.getId(), FINGERPRINT_A, key.getEtag());

        assertTrue(apiKeys.list(user.getId()).isEmpty());
    }

    @Test
    void deletingAUserRemovesItsKeysOnly() {
        StoredUser other = identity.createUser("bob", "Bob", null, null, null);
        apiKeys.upload(user.getId(), pem("a"));
        apiKeys.upload(other.getId(), pem("b"));

        apiKeys.deleteAllOf(user.getId());

        assertTrue(apiKeys.find(TENANCY, user.getId(), FINGERPRINT_A).isEmpty());
        assertEquals(1, apiKeys.list(other.getId()).size());
    }
}
