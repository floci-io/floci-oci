package io.floci.oci.services.psql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.tls.CertificateGenerator;
import io.floci.oci.core.tls.CertificateGenerator.GeneratedCertificate;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.psql.model.StoredDbSystem;
import io.floci.oci.services.vault.VaultSecretsService;
import io.floci.oci.services.vault.model.StoredVaultSecret;
import io.floci.oci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PsqlServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..psqltesttenancy";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..psqltestcompartment";
    private static final String PASSWORD = "BEstrO0ng_#11";

    private EmulatorConfig config;
    private InMemoryStorage<String, StoredDbSystem> systems;
    private WorkRequestService workRequests;
    private PsqlContainerManager containers;
    private VaultSecretsService vaultSecrets;
    private MutableClock clock;
    private CertificateGenerator certificates;
    private PsqlService mock;
    private PsqlService real;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.hostname()).thenReturn(Optional.empty());
        lenient().when(config.services().psql().basePort()).thenReturn(15432);
        lenient().when(config.services().psql().readyTimeoutSeconds()).thenReturn(120);
        systems = new InMemoryStorage<>();
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        vaultSecrets = mock(VaultSecretsService.class);
        clock = new MutableClock();
        // The real generator needs the BouncyCastle provider, which only the running emulator installs.
        certificates = mock(CertificateGenerator.class);
        lenient().when(certificates.generateSelfSignedCertificate(any(), any(), any()))
                .thenReturn(new GeneratedCertificate("-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----\n",
                        "-----BEGIN PRIVATE KEY-----\ntest\n-----END PRIVATE KEY-----\n", "1",
                        Instant.EPOCH, Instant.EPOCH, "CN=test", "CN=test", "SHA256withRSA"));

        containers = mock(PsqlContainerManager.class);
        lenient().when(containers.containerName(any())).thenAnswer(call -> "floci-oci-psql-" + call.getArgument(0));
        lenient().when(containers.volumeName(any())).thenAnswer(call -> "floci-oci-psql-vol-" + call.getArgument(0));
        lenient().when(containers.clientEndpoint(any())).thenReturn(new EndpointInfo("localhost", 15432));
        lenient().when(containers.serverVersion(any())).thenReturn(Optional.of("15.8"));
        lenient().when(containers.isRunning(any())).thenReturn(true);

        // Mock mode has no manager at all; real mode runs its Docker work inline.
        mock = service(null);
        real = service(containers);
    }

    private PsqlService service(PsqlContainerManager manager) {
        return new PsqlService(systems, config, null, workRequests, manager, OciContext.fromConfig(config),
                certificates, vaultSecrets, clock, Runnable::run);
    }

    private static Map<String, Object> createDetails() {
        Map<String, Object> details = new HashMap<>();
        details.put("displayName", "orders-db");
        details.put("compartmentId", COMPARTMENT);
        details.put("dbVersion", "15");
        details.put("shape", "PostgreSQL.VM.Standard.E4.Flex.2.32GB");
        details.put("storageDetails", Map.of("systemType", "OCI_OPTIMIZED_STORAGE", "isRegionallyDurable", true));
        details.put("networkDetails", Map.of("subnetId", "ocid1.subnet.oc1.iad.psqltestsubnet"));
        details.put("credentials", Map.of("username", "adminUser",
                "passwordDetails", Map.of("passwordType", "PLAIN_TEXT", "password", PASSWORD)));
        return details;
    }

    private static Map<String, Object> without(String key) {
        Map<String, Object> details = createDetails();
        details.remove(key);
        return details;
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> details = createDetails();
        details.put(key, value);
        return details;
    }

    private StoredWorkRequest workRequest(String id) {
        return workRequests.get(PsqlService.WR_SERVICE, id);
    }

    // ── Create, in mock mode ───────────────────────────────────────────────────

    @Test
    void createInMockModeIsActiveAtOnceWithEveryFieldClientsRead() {
        PsqlService.Created created = mock.createDbSystem(createDetails());

        Map<String, Object> wire = created.system().toWire();
        assertTrue(wire.get("id").toString().startsWith("ocid1.postgresqldbsystem.oc1.iad."));
        assertEquals("ACTIVE", wire.get("lifecycleState"));
        assertEquals("OCI_OPTIMIZED_STORAGE", wire.get("systemType"));
        assertEquals("STANDALONE_DB_SYSTEM", wire.get("systemRole"));
        assertEquals("15", wire.get("dbVersion"));
        // Terraform rebuilds "PostgreSQL.<shape>.<ocpu>.<mem>GB" from these three.
        assertEquals("VM.Standard.E4.Flex", wire.get("shape"));
        assertEquals(2, wire.get("instanceOcpuCount"));
        assertEquals(32, wire.get("instanceMemorySizeInGBs"));
        assertEquals(1, wire.get("instanceCount"));
        assertEquals("adminUser", wire.get("adminUsername"));
        assertEquals(Map.of("sourceType", "NONE"), wire.get("source"));
        // A sized shape gets a fixed configuration; Terraform derives the shape name from it.
        assertEquals(PsqlCatalog.defaultConfigurationId("15", new PsqlCatalog.Shape("VM.Standard.E4.Flex", 2, 32),
                "oc1", "iad"), wire.get("configId"));
        assertEquals("SUN 00:00", ((Map<?, ?>) wire.get("managementPolicy")).get("maintenanceWindowStart"));
        assertEquals(Map.of("kind", "NONE"), ((Map<?, ?>) wire.get("managementPolicy")).get("backupPolicy"));
        assertEquals(75000L, ((Map<?, ?>) wire.get("storageDetails")).get("iops"));
        assertEquals(1, ((List<?>) wire.get("instances")).size());
        // Terraform plans a change for every computed attribute the response leaves out.
        for (String computed : List.of("instanceCount", "instanceOcpuCount", "instanceMemorySizeInGBs", "configId",
                "systemType", "managementPolicy", "source", "systemTags", "freeformTags", "definedTags")) {
            assertNotNull(wire.get(computed), computed);
        }

        StoredWorkRequest wr = workRequest(created.workRequestId());
        assertEquals("CREATE_POSTGRESQL_DB_SYSTEM", wr.getOperationType());
        assertEquals("SUCCEEDED", wr.getStatus());
        assertNotNull(wr.getTimeFinished());
        // Terraform looks for an entity type containing "dbsystem" and takes its identifier.
        assertTrue(wr.getResources().getFirst().getEntityType().toLowerCase().contains("dbsystem"));
        assertEquals(created.system().getId(), wr.getResources().getFirst().getIdentifier());
    }

    @Test
    void theMasterPasswordIsNeitherReturnedNorStored() throws Exception {
        PsqlService.Created created = mock.createDbSystem(createDetails());

        ObjectMapper mapper = new ObjectMapper();
        assertFalse(mapper.writeValueAsString(created.system().toWire()).contains(PASSWORD));
        assertFalse(mapper.writeValueAsString(created.system()).contains(PASSWORD));
        assertFalse(created.system().toWire().containsKey("credentials"));
    }

    @Test
    void internalFieldsNeverReachTheWire() {
        Map<String, Object> wire = mock.createDbSystem(createDetails()).system().toWire();

        for (String internal : List.of("tenancyId", "region", "hostPort", "containerName", "volumeName",
                "workRequestId", "certificatePem", "privateKeyPem", "endpointHost", "endpointPort", "etag")) {
            assertFalse(wire.containsKey(internal), internal);
        }
    }

    @Test
    void aFlexibleShapeTakesItsSizeFromTheRequest() {
        Map<String, Object> details = with("shape", "PostgreSQL.VM.Standard.E5.Flex");
        details.put("instanceOcpuCount", 4);
        details.put("instanceMemorySizeInGBs", 64);

        StoredDbSystem system = mock.createDbSystem(details).system();

        assertEquals("VM.Standard.E5.Flex", system.getShape());
        assertEquals(4, system.getInstanceOcpuCount());
        assertEquals(64, system.getInstanceMemorySizeInGBs());
        assertEquals(PsqlCatalog.defaultConfigurationId("15", null, "oc1", "iad"), system.getConfigId());
    }

    @Test
    void severalInstancesAreModelledAsSeveralNodes() {
        Map<String, Object> details = with("instanceCount", 3);
        details.put("instancesDetails", List.of(Map.of("displayName", "a"), Map.of("displayName", "b"),
                Map.of("displayName", "c")));

        StoredDbSystem system = mock.createDbSystem(details).system();

        assertEquals(3, system.getInstances().size());
        assertEquals("b", system.getInstances().get(1).getDisplayName());
        assertEquals(system.getInstances().getFirst().getId(),
                mock.primaryDbInstance(system.getId()).get("dbInstanceId"));
        assertEquals(3, ((List<?>) mock.connectionDetails(system.getId()).get("instanceEndpoints")).size());
    }

    // ── Create, validation ─────────────────────────────────────────────────────

    @Test
    void mandatoryFieldsAreMissingParameter() {
        for (String field : List.of("displayName", "compartmentId", "dbVersion", "shape", "storageDetails",
                "networkDetails", "credentials")) {
            OciException e = assertThrows(OciException.class, () -> mock.createDbSystem(without(field)), field);
            assertEquals(400, e.getHttpStatus(), field);
            assertEquals("MissingParameter", e.getCode(), field);
        }
    }

    @Test
    void storageNeedsAnAvailabilityDomainExactlyWhenItIsNotRegionallyDurable() {
        Map<String, Object> zonalWithoutAd = with("storageDetails",
                Map.of("systemType", "OCI_OPTIMIZED_STORAGE", "isRegionallyDurable", false));
        Map<String, Object> regionalWithAd = with("storageDetails", Map.of("systemType", "OCI_OPTIMIZED_STORAGE",
                "isRegionallyDurable", true, "availabilityDomain", "Floc:US-ASHBURN-AD-1"));
        Map<String, Object> zonal = with("storageDetails", Map.of("systemType", "OCI_OPTIMIZED_STORAGE",
                "isRegionallyDurable", false, "availabilityDomain", "Floc:US-ASHBURN-AD-1", "iops", 300000));

        assertEquals("InvalidParameter",
                assertThrows(OciException.class, () -> mock.createDbSystem(zonalWithoutAd)).getCode());
        assertEquals("InvalidParameter",
                assertThrows(OciException.class, () -> mock.createDbSystem(regionalWithAd)).getCode());
        StoredDbSystem system = mock.createDbSystem(zonal).system();
        assertEquals(300000L, system.getStorageDetails().get("iops"));
        assertEquals("Floc:US-ASHBURN-AD-1", system.getInstances().getFirst().getAvailabilityDomain());
    }

    @Test
    void unsupportedInputsAreRejected() {
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> mock.createDbSystem(with("dbVersion", "9.6"))).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> mock.createDbSystem(with("shape", "VM.Standard2.1"))).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> mock.createDbSystem(with("instanceCount", 0))).getCode());

        OciException fromBackup = assertThrows(OciException.class, () -> mock.createDbSystem(
                with("source", Map.of("sourceType", "BACKUP", "backupId", "ocid1.postgresqlbackup.oc1.iad.x"))));
        assertEquals(501, fromBackup.getHttpStatus());

        OciException unknownConfig = assertThrows(OciException.class, () -> mock.createDbSystem(
                with("configId", "ocid1.postgresqlconfiguration.oc1.iad.custom")));
        assertEquals(400, unknownConfig.getHttpStatus());
        assertEquals("RelatedResourceNotAuthorizedOrNotFound", unknownConfig.getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> mock.createDbSystem(
                with("configId", PsqlCatalog.defaultConfigurationId("14", null, "oc1", "iad")))).getCode());
    }

    @Test
    void aVaultSecretSuppliesTheMasterPassword() {
        StoredVaultSecret.StoredSecretVersion version = new StoredVaultSecret.StoredSecretVersion();
        version.setContent(Base64.getEncoder().encodeToString("from-the-vault".getBytes(StandardCharsets.UTF_8)));
        when(vaultSecrets.getBundle("ocid1.vaultsecret.oc1.iad.s", 2L, null))
                .thenReturn(new VaultSecretsService.Bundle(new StoredVaultSecret(), version));
        Map<String, Object> details = with("credentials", Map.of("username", "adminUser", "passwordDetails",
                Map.of("passwordType", "VAULT_SECRET", "secretId", "ocid1.vaultsecret.oc1.iad.s",
                        "secretVersion", "2")));

        real.createDbSystem(details);

        verify(containers).start(any(), eq("from-the-vault"));
    }

    @Test
    void aMissingVaultSecretIsARelatedResourceError() {
        when(vaultSecrets.getBundle(any(), any(), any()))
                .thenThrow(OciException.notAuthorizedOrNotFound("no such secret"));
        Map<String, Object> details = with("credentials", Map.of("username", "adminUser", "passwordDetails",
                Map.of("passwordType", "VAULT_SECRET", "secretId", "ocid1.vaultsecret.oc1.iad.gone",
                        "secretVersion", "1")));

        OciException e = assertThrows(OciException.class, () -> mock.createDbSystem(details));
        assertEquals(400, e.getHttpStatus());
        assertEquals("RelatedResourceNotAuthorizedOrNotFound", e.getCode());
    }

    // ── Lifecycle, with a container ────────────────────────────────────────────

    @Test
    void createStaysCreatingUntilPostgresAcceptsConnections() {
        PsqlService.Created created = real.createDbSystem(createDetails());
        String id = created.system().getId();

        assertEquals("CREATING", real.getDbSystem(id).getLifecycleState());
        assertEquals("IN_PROGRESS", workRequest(created.workRequestId()).getStatus());
        assertNull(workRequest(created.workRequestId()).getTimeFinished());
        verify(containers).start(any(), eq(PASSWORD));
        assertEquals("IncorrectState", assertThrows(OciException.class, () -> real.connectionDetails(id)).getCode());

        when(containers.probeReady(any())).thenReturn(false);
        real.pollReadiness();
        assertEquals("CREATING", real.getDbSystem(id).getLifecycleState());

        when(containers.probeReady(any())).thenReturn(true);
        real.pollReadiness();

        StoredDbSystem active = real.getDbSystem(id);
        assertEquals("ACTIVE", active.getLifecycleState());
        assertEquals("ACTIVE", active.getInstances().getFirst().getLifecycleState());
        assertEquals("15.8", active.getDbVersion());
        StoredWorkRequest wr = workRequest(created.workRequestId());
        assertEquals("SUCCEEDED", wr.getStatus());
        assertNotNull(wr.getTimeFinished());

        Map<String, Object> connection = real.connectionDetails(id);
        assertTrue(connection.get("caCertificate").toString().startsWith("-----BEGIN CERTIFICATE-----"));
        assertEquals(Map.of("fqdn", "localhost", "port", 15432), connection.get("primaryDbEndpoint"));
    }

    @Test
    void aContainerThatExitsFailsTheCreateAndExplainsWhy() {
        PsqlService.Created created = real.createDbSystem(createDetails());
        when(containers.probeReady(any())).thenReturn(false);
        when(containers.isRunning(any())).thenReturn(false);

        real.pollReadiness();

        StoredDbSystem failed = real.getDbSystem(created.system().getId());
        assertEquals("FAILED", failed.getLifecycleState());
        assertEquals("The PostgreSQL container exited before it accepted connections", failed.getLifecycleDetails());
        StoredWorkRequest wr = workRequest(created.workRequestId());
        assertEquals("FAILED", wr.getStatus());
        assertEquals("The PostgreSQL container exited before it accepted connections",
                wr.getErrors().getFirst().getMessage());
        // Once before the start, to clear any leftover, and once more to clean up after the failure.
        verify(containers, times(2)).stop(any());
    }

    @Test
    void aStartThatNeverBecomesReadyFailsAfterTheTimeout() {
        PsqlService.Created created = real.createDbSystem(createDetails());
        when(containers.probeReady(any())).thenReturn(false);

        clock.advance(Duration.ofSeconds(119));
        real.pollReadiness();
        assertEquals("CREATING", real.getDbSystem(created.system().getId()).getLifecycleState());

        clock.advance(Duration.ofSeconds(2));
        real.pollReadiness();
        assertEquals("FAILED", real.getDbSystem(created.system().getId()).getLifecycleState());
        assertEquals("FAILED", workRequest(created.workRequestId()).getStatus());
    }

    @Test
    void aContainerThatCannotStartFailsTheCreate() {
        doThrow(new IllegalStateException("no free port")).when(containers).start(any(), any());

        PsqlService.Created created = real.createDbSystem(createDetails());

        StoredDbSystem failed = real.getDbSystem(created.system().getId());
        assertEquals("FAILED", failed.getLifecycleState());
        assertTrue(failed.getLifecycleDetails().contains("no free port"));
        assertEquals("FAILED", workRequest(created.workRequestId()).getStatus());
    }

    private StoredDbSystem activeRealSystem() {
        PsqlService.Created created = real.createDbSystem(createDetails());
        when(containers.probeReady(any())).thenReturn(true);
        real.pollReadiness();
        return real.getDbSystem(created.system().getId());
    }

    @Test
    void stopAndStartKeepTheSystemAndItsVolume() {
        StoredDbSystem system = activeRealSystem();

        String stop = real.stopDbSystem(system.getId(), null);
        assertEquals("INACTIVE", real.getDbSystem(system.getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(stop).getStatus());
        verify(containers, never()).remove(any());

        String start = real.startDbSystem(system.getId(), null);
        assertEquals("UPDATING", real.getDbSystem(system.getId()).getLifecycleState());
        assertEquals("IN_PROGRESS", workRequest(start).getStatus());
        real.pollReadiness();
        assertEquals("ACTIVE", real.getDbSystem(system.getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(start).getStatus());
    }

    @Test
    void actionsAreRefusedInTheWrongState() {
        StoredDbSystem system = activeRealSystem();

        OciException startActive = assertThrows(OciException.class, () -> real.startDbSystem(system.getId(), null));
        assertEquals(409, startActive.getHttpStatus());
        assertEquals("IncorrectState", startActive.getCode());

        real.stopDbSystem(system.getId(), null);
        assertEquals("IncorrectState",
                assertThrows(OciException.class, () -> real.stopDbSystem(system.getId(), null)).getCode());
    }

    @Test
    void restartNeedsOneOfTheSystemsInstances() {
        StoredDbSystem system = activeRealSystem();
        String instanceId = system.getInstances().getFirst().getId();

        assertEquals("NotAuthorizedOrNotFound", assertThrows(OciException.class,
                () -> real.restartDbInstance(system.getId(),
                        Map.of("dbInstanceId", "ocid1.postgresqldbinstance.oc1.iad.other", "restartType", "NORMAL"),
                        null)).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> real.restartDbInstance(system.getId(),
                        Map.of("dbInstanceId", instanceId, "restartType", "GENTLY"), null)).getCode());

        String restart = real.restartDbInstance(system.getId(),
                Map.of("dbInstanceId", instanceId, "restartType", "NORMAL"), null);
        real.pollReadiness();
        assertEquals("ACTIVE", real.getDbSystem(system.getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(restart).getStatus());
    }

    @Test
    void resettingThePasswordChangesItInTheDatabase() {
        StoredDbSystem system = activeRealSystem();

        String reset = real.resetMasterUserPassword(system.getId(),
                Map.of("passwordDetails", Map.of("passwordType", "PLAIN_TEXT", "password", "N3w_passw0rd#")), null);

        verify(containers).resetPassword(any(), eq("N3w_passw0rd#"));
        assertEquals("ACTIVE", real.getDbSystem(system.getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(reset).getStatus());
    }

    @Test
    void aRejectedPasswordFailsTheWorkRequestAndLeavesTheSystemRunning() {
        StoredDbSystem system = activeRealSystem();
        doThrow(new IllegalStateException("PostgreSQL rejected the password change (exit 3): boom"))
                .when(containers).resetPassword(any(), any());

        String reset = real.resetMasterUserPassword(system.getId(),
                Map.of("passwordDetails", Map.of("passwordType", "PLAIN_TEXT", "password", "x")), null);

        assertEquals("ACTIVE", real.getDbSystem(system.getId()).getLifecycleState());
        assertEquals("FAILED", workRequest(reset).getStatus());
        assertTrue(workRequest(reset).getErrors().getFirst().getMessage().contains("rejected the password change"));
    }

    @Test
    void aDeletedSystemIsStillReturnedAsDeleted() {
        StoredDbSystem system = activeRealSystem();

        String delete = real.deleteDbSystem(system.getId(), null);

        verify(containers).remove(any());
        assertEquals("DELETED", real.getDbSystem(system.getId()).getLifecycleState());
        StoredWorkRequest wr = workRequest(delete);
        assertEquals("DELETE_POSTGRESQL_DB_SYSTEM", wr.getOperationType());
        assertEquals("SUCCEEDED", wr.getStatus());
        assertEquals("IncorrectState",
                assertThrows(OciException.class, () -> real.deleteDbSystem(system.getId(), null)).getCode());
        assertEquals("NotAuthorizedOrNotFound", assertThrows(OciException.class,
                () -> real.updateDbSystem(system.getId(), Map.of("displayName", "x"), null)).getCode());
        assertTrue(real.resourcesIn(TENANCY, COMPARTMENT).isEmpty());
    }

    @Test
    void deletingASystemThatIsStillStartingCancelsTheCreate() {
        PsqlService.Created created = real.createDbSystem(createDetails());

        real.deleteDbSystem(created.system().getId(), null);
        real.pollReadiness();

        assertEquals("CANCELED", workRequest(created.workRequestId()).getStatus());
        assertEquals("DELETED", real.getDbSystem(created.system().getId()).getLifecycleState());
    }

    // ── Reads and metadata ─────────────────────────────────────────────────────

    @Test
    void anUnknownSystemIsNotAuthorizedOrNotFound() {
        OciException e = assertThrows(OciException.class,
                () -> mock.getDbSystem("ocid1.postgresqldbsystem.oc1.iad.missing"));
        assertEquals(404, e.getHttpStatus());
        assertEquals("NotAuthorizedOrNotFound", e.getCode());
    }

    @Test
    void updateChangesMetadataAndTheEtag() {
        StoredDbSystem system = mock.createDbSystem(createDetails()).system();
        String etag = system.getEtag();

        String update = mock.updateDbSystem(system.getId(), Map.of("displayName", "renamed",
                "shape", "PostgreSQL.VM.Standard.E5.Flex.4.64GB",
                "managementPolicy", Map.of("maintenanceWindowStart", "THU 15:00"),
                "freeformTags", Map.of("env", "test")), etag);

        StoredDbSystem updated = mock.getDbSystem(system.getId());
        assertEquals("renamed", updated.getDisplayName());
        assertEquals("VM.Standard.E5.Flex", updated.getShape());
        assertEquals(4, updated.getInstanceOcpuCount());
        assertEquals("THU 15:00", updated.getManagementPolicy().get("maintenanceWindowStart"));
        assertEquals(Map.of("kind", "NONE"), updated.getManagementPolicy().get("backupPolicy"));
        assertEquals(Map.of("env", "test"), updated.getFreeformTags());
        assertNotEquals(etag, updated.getEtag());
        assertEquals("UPDATE_POSTGRESQL_DB_SYSTEM", workRequest(update).getOperationType());

        OciException stale = assertThrows(OciException.class,
                () -> mock.updateDbSystem(system.getId(), Map.of("displayName", "again"), etag));
        assertEquals(412, stale.getHttpStatus());
    }

    @Test
    void changeCompartmentMovesTheSystem() {
        StoredDbSystem system = mock.createDbSystem(createDetails()).system();

        String move = mock.changeCompartment(system.getId(), "ocid1.compartment.oc1..other", null);

        assertEquals("ocid1.compartment.oc1..other", mock.getDbSystem(system.getId()).getCompartmentId());
        assertEquals("MOVE_POSTGRESQL_DB_SYSTEM", workRequest(move).getOperationType());
        assertEquals(List.of("DB system " + system.getId()),
                mock.resourcesIn(TENANCY, "ocid1.compartment.oc1..other"));
    }

    @Test
    void listFiltersAndSorts() {
        StoredDbSystem first = mock.createDbSystem(with("displayName", "b-first")).system();
        clock.advance(Duration.ofSeconds(1));
        StoredDbSystem second = mock.createDbSystem(with("displayName", "a-second")).system();

        assertEquals(List.of(second.getId(), first.getId()),
                mock.listDbSystems(COMPARTMENT, null, null, null, null, null).stream()
                        .map(StoredDbSystem::getId).toList());
        assertEquals(List.of(second.getId(), first.getId()),
                mock.listDbSystems(COMPARTMENT, null, null, null, "displayName", null).stream()
                        .map(StoredDbSystem::getId).toList());
        assertEquals(List.of(first.getId()),
                mock.listDbSystems(COMPARTMENT, "ACTIVE", "b-first", null, null, null).stream()
                        .map(StoredDbSystem::getId).toList());
        assertTrue(mock.listDbSystems("ocid1.compartment.oc1..empty", null, null, null, null, null).isEmpty());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> mock.listDbSystems(COMPARTMENT, "SLEEPING", null, null, null, null)).getCode());
    }

    @Test
    void resetRemovesEverySidecarAndEverySystem() {
        StoredDbSystem system = activeRealSystem();

        real.clear();

        // Purged, not removed: the volume goes even when prune-volumes-on-delete is off.
        verify(containers).purge(any());
        verify(containers, never()).remove(any());
        assertThrows(OciException.class, () -> real.getDbSystem(system.getId()));
    }

    // ── Late outcomes and interrupted operations ───────────────────────────────

    /** Shares the store with {@link #real} but never runs its Docker work, as if it were cut off. */
    private PsqlService interrupted() {
        return new PsqlService(systems, config, null, workRequests, containers, OciContext.fromConfig(config),
                certificates, vaultSecrets, clock, task -> { });
    }

    @Test
    void aStartThatFinishesAfterADeleteDoesNotBringTheSystemBack() {
        doAnswer(call -> {
            real.deleteDbSystem(call.<StoredDbSystem>getArgument(0).getId(), null);
            return null;
        }).when(containers).start(any(), any());

        PsqlService.Created created = real.createDbSystem(createDetails());
        when(containers.isRunning(any())).thenReturn(false);
        real.pollReadiness();

        assertEquals("DELETED", real.getDbSystem(created.system().getId()).getLifecycleState());
        assertEquals("CANCELED", workRequest(created.workRequestId()).getStatus());
    }

    @Test
    void aStartThatFinishesAfterAResetRemovesWhatItStarted() {
        doAnswer(call -> {
            real.clear();
            return null;
        }).when(containers).start(any(), any());

        PsqlService.Created created = real.createDbSystem(createDetails());
        real.pollReadiness();

        assertThrows(OciException.class, () -> real.getDbSystem(created.system().getId()));
        assertTrue(systems.keys().isEmpty());
        // Once by the reset itself, once for the container the late start brought up.
        verify(containers, times(2)).purge(any());
    }

    @Test
    void aRestartFailsAnInterruptedPasswordResetAndBringsTheSystemBack() {
        StoredDbSystem system = activeRealSystem();
        String reset = interrupted().resetMasterUserPassword(system.getId(),
                Map.of("passwordDetails", Map.of("passwordType", "PLAIN_TEXT", "password", "N3w_passw0rd#")), null);

        real.restartPersistedSystems();
        real.pollReadiness();

        assertEquals("FAILED", workRequest(reset).getStatus());
        assertEquals(PsqlService.INTERRUPTED, workRequest(reset).getErrors().getFirst().getMessage());
        assertEquals("ACTIVE", real.getDbSystem(system.getId()).getLifecycleState());
        verify(containers, never()).resetPassword(any(), any());
    }

    @Test
    void aRestartFailsAnInterruptedCreate() {
        PsqlService.Created created = interrupted().createDbSystem(createDetails());

        real.restartPersistedSystems();

        assertEquals("FAILED", real.getDbSystem(created.system().getId()).getLifecycleState());
        assertEquals("FAILED", workRequest(created.workRequestId()).getStatus());
        verify(containers, never()).start(any(), any());
    }

    @Test
    void aRestartFinishesAnInterruptedDelete() {
        StoredDbSystem system = activeRealSystem();
        String delete = interrupted().deleteDbSystem(system.getId(), null);
        assertEquals("DELETING", real.getDbSystem(system.getId()).getLifecycleState());

        real.restartPersistedSystems();

        assertEquals("DELETED", real.getDbSystem(system.getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(delete).getStatus());
        verify(containers).remove(any());
    }

    @Test
    void aRejectedUpdateChangesNothing() {
        StoredDbSystem system = mock.createDbSystem(createDetails()).system();
        String etag = system.getEtag();

        assertThrows(OciException.class, () -> mock.updateDbSystem(system.getId(),
                Map.of("displayName", "renamed", "shape", "NotAShape"), null));
        OciException badIops = assertThrows(OciException.class, () -> mock.updateDbSystem(system.getId(),
                Map.of("displayName", "renamed", "storageDetails", Map.of("iops", "fast")), null));

        assertEquals("InvalidParameter", badIops.getCode());
        StoredDbSystem unchanged = mock.getDbSystem(system.getId());
        assertEquals("orders-db", unchanged.getDisplayName());
        assertEquals(etag, unchanged.getEtag());
    }

    @Test
    void theCertificateNamesARemoteDockerHost() {
        when(containers.daemonHost()).thenReturn("docker.example");

        real.createDbSystem(createDetails());

        ArgumentCaptor<List<String>> names = ArgumentCaptor.captor();
        verify(certificates).generateSelfSignedCertificate(any(), names.capture(), any());
        assertTrue(names.getValue().contains("docker.example"), names.getValue().toString());
        assertTrue(names.getValue().contains("localhost"), names.getValue().toString());
    }
}
