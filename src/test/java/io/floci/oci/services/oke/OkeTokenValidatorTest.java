package io.floci.oci.services.oke;

import io.floci.oci.core.auth.AuthContext;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkeTokenValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final String REGION = "us-ashburn-1";
    private static final String CLUSTER_ID = "ocid1.cluster.oc1.iad.tokentest001";
    private static final String TENANCY = "ocid1.tenancy.oc1..tokentenancy";
    private static final String USER = "ocid1.user.oc1..tokenuser";

    private OkeTokenValidator validator;
    private StoredOkeCluster cluster;

    @BeforeEach
    void setUp() {
        validator = new OkeTokenValidator(Clock.fixed(NOW, ZoneOffset.UTC));
        cluster = new StoredOkeCluster();
        cluster.setId(CLUSTER_ID);
        cluster.setRegion(REGION);
        cluster.setTenancyId(TENANCY);
    }

    @Test
    void acceptsATokenMintedLikeTheOciCli() {
        Optional<AuthContext> caller = validator.validate(
                ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW), cluster);

        assertTrue(caller.isPresent());
        assertEquals(TENANCY, caller.get().tenancyId());
        assertEquals(USER, caller.get().userId());
    }

    @Test
    void acceptsStandardBase64AsThirdPartyMintersUseIt() {
        String urlSafe = ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW);
        String standard = Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(urlSafe));

        assertTrue(validator.validate(standard, cluster).isPresent());
    }

    @Test
    void acceptsDatesWithinFiveMinutesEitherSide() {
        assertTrue(validator.validate(mintAt(NOW.minus(Duration.ofMinutes(5))), cluster).isPresent());
        assertTrue(validator.validate(mintAt(NOW.plus(Duration.ofMinutes(5))), cluster).isPresent());
    }

    @Test
    void rejectsStaleAndFutureDates() {
        assertRejected(mintAt(NOW.minus(Duration.ofMinutes(5)).minusSeconds(1)));
        assertRejected(mintAt(NOW.plus(Duration.ofMinutes(5)).plusSeconds(1)));
    }

    @Test
    void rejectsATokenForAnotherCluster() {
        assertRejected(ClusterTokenMinter.mint(REGION, "ocid1.cluster.oc1.iad.othercluster", TENANCY, USER, NOW));
    }

    @Test
    void rejectsATokenFromAnotherTenancy() {
        assertRejected(ClusterTokenMinter.mint(REGION, CLUSTER_ID, "ocid1.tenancy.oc1..someoneelse", USER, NOW));
    }

    @Test
    void rejectsATokenSignedForAnotherRegion() {
        assertRejected(ClusterTokenMinter.mint("us-phoenix-1", CLUSTER_ID, TENANCY, USER, NOW));
    }

    @Test
    void rejectsUrlsThatAreNotTheRealContainerEngineHost() {
        String minted = decoded(ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW));
        assertRejected(ClusterTokenMinter.encode(minted.replace("https://", "http://")));
        assertRejected(ClusterTokenMinter.encode(minted.replace("oraclecloud.com", "oraclecloud.com.evil.example")));
        assertRejected(ClusterTokenMinter.encode(minted.replace(".oraclecloud.com/", ".oraclecloud.com:8443/")));
        assertRejected(ClusterTokenMinter.encode(minted.replace("/cluster_request/", "/clusters/")));
        assertRejected(ClusterTokenMinter.encode(minted.replace("https://", "https://user@")));
        assertRejected(ClusterTokenMinter.encode(minted + "#fragment"));
    }

    @Test
    void rejectsMissingDuplicateAndUnknownQueryParameters() {
        String minted = decoded(ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW));
        String withoutDate = minted.substring(0, minted.indexOf("&date="));
        String withoutAuthorization = minted.substring(0, minted.indexOf('?') + 1)
                + minted.substring(minted.indexOf("date="));

        assertRejected(ClusterTokenMinter.encode(withoutDate));
        assertRejected(ClusterTokenMinter.encode(withoutAuthorization));
        assertRejected(ClusterTokenMinter.encode(minted + "&date=Thu%2C+01+Oct+2026+12%3A00%3A00+GMT"));
        assertRejected(ClusterTokenMinter.encode(minted + "&extra=1"));
    }

    @Test
    void acceptsTheOptionalOboToken() {
        String minted = decoded(ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW));

        assertTrue(validator.validate(ClusterTokenMinter.encode(minted + "&opc-obo-token=delegation"), cluster).isPresent());
    }

    @Test
    void rejectsSignaturesOverOtherHeadersOrAlgorithms() {
        String minted = decoded(ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW));

        assertRejected(ClusterTokenMinter.encode(minted.replace("headers%3D%22date+%28request-target%29+host%22",
                "headers%3D%22date+host%22")));
        assertRejected(ClusterTokenMinter.encode(minted.replace("rsa-sha256", "hmac-sha256")));
        assertRejected(ClusterTokenMinter.encode(minted.replaceAll("signature%3D%22.*?%22", "signature%3D%22%22")));
    }

    @Test
    void rejectsSecurityTokenKeyIdsItCannotBindToATenancy() {
        String minted = decoded(ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, NOW));
        String keyId = "keyId%3D%22" + TENANCY + "%2F" + USER + "%2Faa%3Abb%3Acc%22";
        assertTrue(minted.contains(keyId), minted);

        assertRejected(ClusterTokenMinter.encode(minted.replace(keyId, "keyId%3D%22ST%24eyJhbGciOi%22")));
    }

    @Test
    void rejectsGarbageAndOversizedTokens() {
        assertRejected(null);
        assertRejected("");
        assertRejected("!!!not base64!!!");
        assertRejected(ClusterTokenMinter.encode("not a url at all"));
        assertRejected("A".repeat(OkeTokenValidator.MAX_TOKEN_LENGTH + 1));
    }

    private String mintAt(Instant signedAt) {
        return ClusterTokenMinter.mint(REGION, CLUSTER_ID, TENANCY, USER, signedAt);
    }

    private void assertRejected(String token) {
        assertTrue(validator.validate(token, cluster).isEmpty(), () -> "accepted: " + decodedOrRaw(token));
    }

    private static String decoded(String token) {
        return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    }

    private static String decodedOrRaw(String token) {
        if (token == null) {
            return "null";
        }
        try {
            return decoded(token);
        } catch (IllegalArgumentException notBase64Url) {
            return token;
        }
    }
}
