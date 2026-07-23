package io.floci.oci.compat;

import com.oracle.bmc.Region;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.objectstorage.ObjectStorageClient;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Shared client factory for the compat suite. Builds a SimpleAuthenticationDetailsProvider
 * from a locally generated RSA key — the emulator parses but never verifies the signature,
 * so any key works.
 */
public final class EmulatorFixture {

    public static final String ENDPOINT =
            System.getenv().getOrDefault("FLOCI_OCI_ENDPOINT", "http://localhost:4599");
    public static final String TENANCY = System.getenv().getOrDefault("FLOCI_OCI_TENANCY",
            "ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000");
    public static final String NAMESPACE =
            System.getenv().getOrDefault("FLOCI_OCI_NAMESPACE", "floci-local");
    public static final String USER =
            "ocid1.user.oc1..compattestuser000000000000000000000000000000000000000000000";
    public static final String FINGERPRINT = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99";

    private static final SimpleAuthenticationDetailsProvider AUTH = buildAuth();

    private EmulatorFixture() {
    }

    public static IdentityClient identity() {
        IdentityClient client = IdentityClient.builder()
                .region(Region.US_ASHBURN_1)
                .build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static ObjectStorageClient objectStorage() {
        ObjectStorageClient client = ObjectStorageClient.builder()
                .region(Region.US_ASHBURN_1)
                .build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    private static SimpleAuthenticationDetailsProvider buildAuth() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            String pem = toPem(pair);
            return SimpleAuthenticationDetailsProvider.builder()
                    .tenantId(TENANCY)
                    .userId(USER)
                    .fingerprint(FINGERPRINT)
                    .privateKeySupplier(() -> new ByteArrayInputStream(pem.getBytes()))
                    .region(Region.US_ASHBURN_1)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate test key", e);
        }
    }

    private static String toPem(KeyPair pair) {
        StringWriter writer = new StringWriter();
        writer.write("-----BEGIN PRIVATE KEY-----\n");
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(pair.getPrivate().getEncoded());
        writer.write(base64);
        writer.write("\n-----END PRIVATE KEY-----\n");
        return writer.toString();
    }
}
