package io.floci.oci.services.identity;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.auth.AuthContext;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.model.StoredAuthToken;
import io.floci.oci.services.identity.model.StoredUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class AuthTokenServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..test";

    private IdentityService identity;
    private AuthTokenService authTokens;
    private StoredUser user;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        identity = new IdentityService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), config, new WorkRequestService(new InMemoryStorage<>(), config));
        authTokens = new AuthTokenService(new InMemoryStorage<>(), identity, OciContext.fromConfig(config));
        user = identity.createUser("alice", "Alice", null, null, null);
    }

    @Test
    void createReturnsTheTokenOnceAndKeepsOnlyItsHash() {
        AuthTokenService.CreatedToken created = authTokens.create(user.getId(), "docker");

        StoredAuthToken token = created.token();
        assertTrue(token.getId().startsWith("ocid1.credential.oc1.."), token.getId());
        assertEquals(user.getId(), token.getUserId());
        assertEquals("alice", token.getUserName());
        assertEquals("docker", token.getDescription());
        assertEquals("ACTIVE", token.getLifecycleState());
        assertNotNull(token.getTimeCreated());
        assertNotNull(token.getEtag());
        assertEquals(AuthTokenService.TOKEN_LENGTH, created.value().length());
        assertEquals(AuthTokenService.sha256(created.value()), token.getTokenSha256());
        assertNotEquals(created.value(), token.getTokenSha256());
        assertEquals(List.of(token.getId()),
                authTokens.list(user.getId()).stream().map(StoredAuthToken::getId).toList());
    }

    @Test
    void anEmptyDescriptionIsAllowedButAMissingOneIsNot() {
        assertEquals("", authTokens.create(user.getId(), "").token().getDescription());

        OciException e = assertThrows(OciException.class, () -> authTokens.create(user.getId(), null));
        assertEquals(400, e.getHttpStatus());
        assertEquals("MissingParameter", e.getCode());
        assertEquals("description is required", e.getMessage());
    }

    @Test
    void aUserHoldsAtMostTwoTokens() {
        authTokens.create(user.getId(), "one");
        authTokens.create(user.getId(), "two");

        OciException e = assertThrows(OciException.class, () -> authTokens.create(user.getId(), "three"));
        assertEquals(400, e.getHttpStatus());
        assertEquals("LimitExceeded", e.getCode());
        assertEquals("A user can have up to 2 auth tokens at a time", e.getMessage());
        assertEquals(2, authTokens.list(user.getId()).size());
    }

    @Test
    void updateChangesTheDescriptionAndHonoursIfMatch() {
        StoredAuthToken token = authTokens.create(user.getId(), "before").token();
        String etag = token.getEtag();

        assertEquals("NoEtagMatch", assertThrows(OciException.class,
                () -> authTokens.update(user.getId(), token.getId(), "after", "stale")).getCode());
        StoredAuthToken updated = authTokens.update(user.getId(), token.getId(), "after", etag);

        assertEquals("after", updated.getDescription());
        assertNotEquals(etag, updated.getEtag());
        assertEquals("after", authTokens.update(user.getId(), token.getId(), null, null).getDescription());
    }

    @Test
    void deleteHonoursIfMatch() {
        StoredAuthToken token = authTokens.create(user.getId(), "docker").token();

        assertEquals("NoEtagMatch", assertThrows(OciException.class,
                () -> authTokens.delete(user.getId(), token.getId(), "stale")).getCode());
        authTokens.delete(user.getId(), token.getId(), token.getEtag());

        assertTrue(authTokens.list(user.getId()).isEmpty());
    }

    @Test
    void anUnknownUserOrTokenIsNotFound() {
        StoredUser other = identity.createUser("bob", "Bob", null, null, null);
        StoredAuthToken bobs = authTokens.create(other.getId(), "bob").token();

        assertEquals("NotAuthorizedOrNotFound", assertThrows(OciException.class,
                () -> authTokens.create("ocid1.user.oc1..missing", "x")).getCode());
        assertEquals("NotAuthorizedOrNotFound", assertThrows(OciException.class,
                () -> authTokens.delete(user.getId(), "ocid1.credential.oc1..missing", null)).getCode());
        OciException wrongOwner = assertThrows(OciException.class,
                () -> authTokens.update(user.getId(), bobs.getId(), "mine", null));
        assertEquals("NotAuthorizedOrNotFound", wrongOwner.getCode());
        assertEquals("Authorization failed or requested resource not found: auth token " + bobs.getId(),
                wrongOwner.getMessage());
    }

    @Test
    void resolveMatchesTheUserNameAndTokenInAnAcceptedTenancy() {
        String value = authTokens.create(user.getId(), "docker").value();

        Optional<AuthContext> caller = authTokens.resolve(TENANCY::equals, "alice", value);
        assertTrue(caller.isPresent());
        assertEquals(TENANCY, caller.get().tenancyId());
        assertEquals(user.getId(), caller.get().userId());

        assertTrue(authTokens.resolve(TENANCY::equals, "alice", value + "x").isEmpty());
        assertTrue(authTokens.resolve(TENANCY::equals, "bob", value).isEmpty());
        assertTrue(authTokens.resolve(t -> false, "alice", value).isEmpty());
        assertTrue(authTokens.resolve(TENANCY::equals, null, value).isEmpty());
    }

    @Test
    void deletingAUserRemovesItsTokensOnly() {
        StoredUser other = identity.createUser("bob", "Bob", null, null, null);
        String alices = authTokens.create(user.getId(), "a").value();
        authTokens.create(other.getId(), "b");

        authTokens.deleteAllOf(user.getId());

        assertTrue(authTokens.resolve(TENANCY::equals, "alice", alices).isEmpty());
        assertEquals(1, authTokens.list(other.getId()).size());
    }
}
