package io.floci.oci.compat;

import com.oracle.bmc.Region;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.containerengine.ContainerEngineClient;
import com.oracle.bmc.functions.FunctionsManagementClient;
import com.oracle.bmc.functions.WorkRequestManagementClient;
import com.oracle.bmc.governancerulescontrolplane.GovernanceRuleClient;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.keymanagement.KmsCryptoClient;
import com.oracle.bmc.keymanagement.KmsManagementClient;
import com.oracle.bmc.keymanagement.KmsVaultClient;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.psql.PostgresqlClient;
import com.oracle.bmc.queue.QueueAdminClient;
import com.oracle.bmc.queue.QueueClient;
import com.oracle.bmc.secrets.SecretsClient;
import com.oracle.bmc.streaming.StreamAdminClient;
import com.oracle.bmc.streaming.StreamClient;
import com.oracle.bmc.tenantmanagercontrolplane.DomainClient;
import com.oracle.bmc.tenantmanagercontrolplane.DomainGovernanceClient;
import com.oracle.bmc.tenantmanagercontrolplane.LinkClient;
import com.oracle.bmc.tenantmanagercontrolplane.OrganizationClient;
import com.oracle.bmc.tenantmanagercontrolplane.RecipientInvitationClient;
import com.oracle.bmc.tenantmanagercontrolplane.SenderInvitationClient;
import com.oracle.bmc.tenantmanagercontrolplane.SubscriptionClient;
import com.oracle.bmc.tenantmanagercontrolplane.WorkRequestClient;
import com.oracle.bmc.vault.VaultsClient;

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

    private static final SimpleAuthenticationDetailsProvider AUTH = buildAuth(TENANCY);

    private EmulatorFixture() {
    }

    public static IdentityClient identity() {
        IdentityClient client = IdentityClient.builder()
                .region(Region.US_ASHBURN_1)
                .build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    /** An Identity client signing as {@code tenancyId}, e.g. a child tenancy. */
    public static IdentityClient identityAs(String tenancyId) {
        IdentityClient client = IdentityClient.builder()
                .region(Region.US_ASHBURN_1)
                .build(buildAuth(tenancyId));
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static OrganizationClient organization() {
        OrganizationClient client = OrganizationClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static LinkClient link() {
        LinkClient client = LinkClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static SubscriptionClient subscriptionsAs(String tenancyId) {
        SubscriptionClient client = SubscriptionClient.builder()
                .region(Region.US_ASHBURN_1).build(buildAuth(tenancyId));
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static DomainClient domainsAs(String tenancyId) {
        DomainClient client = DomainClient.builder()
                .region(Region.US_ASHBURN_1).build(buildAuth(tenancyId));
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static DomainGovernanceClient domainGovernancesAs(String tenancyId) {
        DomainGovernanceClient client = DomainGovernanceClient.builder()
                .region(Region.US_ASHBURN_1).build(buildAuth(tenancyId));
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static SenderInvitationClient senderInvitationsAs(String tenancyId) {
        SenderInvitationClient client = SenderInvitationClient.builder()
                .region(Region.US_ASHBURN_1).build(buildAuth(tenancyId));
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static RecipientInvitationClient recipientInvitationsAs(String tenancyId) {
        RecipientInvitationClient client = RecipientInvitationClient.builder()
                .region(Region.US_ASHBURN_1).build(buildAuth(tenancyId));
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static GovernanceRuleClient governanceRulesAs(String tenancyId) {
        GovernanceRuleClient client = GovernanceRuleClient.builder()
                .region(Region.US_ASHBURN_1).build(buildAuth(tenancyId));
        client.setEndpoint(ENDPOINT);
        return client;
    }

    // Qualified: tenantmanagercontrolplane.WorkRequestClient (imported) has the same simple name.
    public static com.oracle.bmc.governancerulescontrolplane.WorkRequestClient governanceWorkRequestsAs(
            String tenancyId) {
        com.oracle.bmc.governancerulescontrolplane.WorkRequestClient client =
                com.oracle.bmc.governancerulescontrolplane.WorkRequestClient.builder()
                        .region(Region.US_ASHBURN_1).build(buildAuth(tenancyId));
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static WorkRequestClient organizationsWorkRequests() {
        WorkRequestClient client = WorkRequestClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static QueueAdminClient queueAdmin() {
        QueueAdminClient client = QueueAdminClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static QueueClient queueData() {
        QueueClient client = QueueClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static KmsVaultClient kmsVault() {
        KmsVaultClient client = KmsVaultClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static PostgresqlClient postgresql() {
        PostgresqlClient client = PostgresqlClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static ContainerEngineClient containerEngine() {
        ContainerEngineClient client = ContainerEngineClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static KmsManagementClient kmsManagement(String endpoint) {
        return KmsManagementClient.builder()
                .endpoint(endpoint).build(AUTH);
    }

    public static KmsCryptoClient kmsCrypto(String endpoint) {
        return KmsCryptoClient.builder()
                .endpoint(endpoint).build(AUTH);
    }

    public static VaultsClient vaults() {
        VaultsClient client = VaultsClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static SecretsClient secrets() {
        SecretsClient client = SecretsClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static StreamAdminClient streamAdmin() {
        StreamAdminClient client = StreamAdminClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static StreamClient streamData(String endpoint) {
        return StreamClient.builder().endpoint(endpoint).build(AUTH);
    }

    public static FunctionsManagementClient functionsManagement() {
        FunctionsManagementClient client = FunctionsManagementClient.builder()
                .region(Region.US_ASHBURN_1).build(AUTH);
        client.setEndpoint(ENDPOINT);
        return client;
    }

    public static WorkRequestManagementClient functionsWorkRequests() {
        WorkRequestManagementClient client = WorkRequestManagementClient.builder()
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

    private static SimpleAuthenticationDetailsProvider buildAuth(String tenancyId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            String pem = toPem(pair);
            return SimpleAuthenticationDetailsProvider.builder()
                    .tenantId(tenancyId)
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
