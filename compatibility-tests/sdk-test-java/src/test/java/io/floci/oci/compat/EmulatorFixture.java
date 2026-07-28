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

    public static com.oracle.bmc.queue.QueueAdminClient queueAdmin() {
        var client = com.oracle.bmc.queue.QueueAdminClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static com.oracle.bmc.queue.QueueClient queueData() {
        var client = com.oracle.bmc.queue.QueueClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static com.oracle.bmc.keymanagement.KmsVaultClient kmsVault() {
        var client = com.oracle.bmc.keymanagement.KmsVaultClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static com.oracle.bmc.keymanagement.KmsManagementClient kmsManagement(String endpoint) {
        return com.oracle.bmc.keymanagement.KmsManagementClient.builder()
                .endpoint(endpoint).build(AUTH);
    }

    public static com.oracle.bmc.keymanagement.KmsCryptoClient kmsCrypto(String endpoint) {
        return com.oracle.bmc.keymanagement.KmsCryptoClient.builder()
                .endpoint(endpoint).build(AUTH);
    }

    public static com.oracle.bmc.vault.VaultsClient vaults() {
        var client = com.oracle.bmc.vault.VaultsClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static com.oracle.bmc.secrets.SecretsClient secrets() {
        var client = com.oracle.bmc.secrets.SecretsClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static com.oracle.bmc.streaming.StreamAdminClient streamAdmin() {
        var client = com.oracle.bmc.streaming.StreamAdminClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static com.oracle.bmc.streaming.StreamClient streamData(String endpoint) {
        return com.oracle.bmc.streaming.StreamClient.builder().endpoint(endpoint).build(AUTH);
    }

    public static com.oracle.bmc.functions.FunctionsManagementClient functionsManagement() {
        var client = com.oracle.bmc.functions.FunctionsManagementClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
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
