package io.floci.oci.services.artifacts;

import io.floci.oci.core.auth.AuthContext;
import io.floci.oci.core.auth.AuthTokenResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OcirLoginTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..alpha";
    private static final String OTHER_TENANCY = "ocid1.tenancy.oc1..beta";
    private static final Map<String, String> NAMESPACES = Map.of(TENANCY, "alphans", OTHER_TENANCY, "betans");
    private static final String TOKEN = "s3cr3t:with(colon)";

    /** alice in {@link #TENANCY} owns {@link #TOKEN}. */
    private static final AuthTokenResolver TOKENS = (inTenancy, userName, token) ->
            inTenancy.test(TENANCY) && "alice".equals(userName) && TOKEN.equals(token)
                    ? Optional.of(AuthContext.unknown(TENANCY, "ocid1.user.oc1..alice"))
                    : Optional.empty();

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-27T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private OcirLogin strict() {
        return new OcirLogin(true, TOKENS, NAMESPACES::get, clock);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void outsideStrictModeAnyoneGetsATokenAndAnyBearerIsAccepted() {
        OcirLogin open = new OcirLogin(false, TOKENS, NAMESPACES::get, clock);

        OcirLogin.Login login = open.login(null);
        assertFalse(login.isRejected());
        assertNotNull(login.token());
        assertTrue(open.accepts("anything"));
        assertTrue(open.allows("anything", OTHER_TENANCY));
    }

    @Test
    void strictModeIssuesATokenForTheNamespaceUserAndAuthToken() {
        OcirLogin login = strict();

        OcirLogin.Login issued = login.login(basic("alphans/alice", TOKEN));

        assertFalse(issued.isRejected());
        assertTrue(login.accepts(issued.token()));
        assertTrue(login.allows(issued.token(), TENANCY));
        assertFalse(login.allows(issued.token(), OTHER_TENANCY));
        assertFalse(login.accepts("made-up"));
        assertFalse(login.accepts(null));
    }

    @Test
    void anIdentityDomainSegmentIsAccepted() {
        assertFalse(strict().login(basic("alphans/oracleidentitycloudservice/alice", TOKEN)).isRejected());
    }

    @Test
    void wrongCredentialsAreRejected() {
        OcirLogin login = strict();

        assertEquals("the username or auth token is not valid", login.login(basic("alphans/alice", "wrong")).rejection());
        assertEquals("the username or auth token is not valid", login.login(basic("betans/alice", TOKEN)).rejection());
        assertEquals("the username or auth token is not valid", login.login(basic("alphans/bob", TOKEN)).rejection());
        String required = "Basic credentials <tenancy-namespace>/<username> and an auth token are required";
        assertEquals(required, login.login(null).rejection());
        assertEquals(required, login.login(basic("alice", TOKEN)).rejection());
        assertEquals(required, login.login(basic("a/b/c/alice", TOKEN)).rejection());
        assertEquals(required, login.login("Basic !!!").rejection());
        assertEquals(required, login.login("Bearer abc").rejection());
    }

    @Test
    void anIssuedTokenExpires() {
        OcirLogin login = strict();
        String token = login.login(basic("alphans/alice", TOKEN)).token();

        now.set(now.get().plus(OcirLogin.TOKEN_LIFETIME));

        assertFalse(login.accepts(token));
    }
}
