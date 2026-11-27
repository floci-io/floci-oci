package io.floci.oci.services.database;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.NetworkReferences;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.database.AutonomousDatabaseContainerManager.Health;
import io.floci.oci.services.database.model.StoredAutonomousDatabase;
import io.floci.oci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The service with mock mode off: its lifecycle over a container manager whose Docker work is mocked. */
class AutonomousDatabaseSidecarServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..adbtesttenancy";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..adbtestcompartment";
    private static final String PASSWORD = "Welcome12345#";
    private static final String TNSNAMES = """
            orders_high = (description= (retry_count=20)(retry_delay=3)(address=(protocol=tcps)(port=1522)(host=localhost))(connect_data=(service_name=orders_high.adb.oraclecloud.com))(security=(SSL_SERVER_DN_MATCH=TRUE)(SSL_SERVER_CERT_DN="CN=localhost")))
            orders_high_tls = (description= (retry_count=20)(retry_delay=3)(address=(protocol=tcps)(port=1521)(host=localhost))(connect_data=(service_name=orders_high.adb.oraclecloud.com))(security=(ssl_server_dn_match=no)))
            orders_low = (description= (retry_count=20)(retry_delay=3)(address=(protocol=tcps)(port=1522)(host=localhost))(connect_data=(service_name=orders_low.adb.oraclecloud.com))(security=(SSL_SERVER_DN_MATCH=TRUE)(SSL_SERVER_CERT_DN="CN=localhost")))
            """;

    private EmulatorConfig config;
    private InMemoryStorage<String, StoredAutonomousDatabase> databases;
    private WorkRequestService workRequests;
    private MutableClock clock;
    private AutonomousDatabaseContainerManager containers;
    private AutonomousDatabaseService service;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.hostname()).thenReturn(Optional.empty());
        lenient().when(config.services().database().mock()).thenReturn(false);
        lenient().when(config.services().database().readyTimeoutSeconds()).thenReturn(600);
        databases = new InMemoryStorage<>();
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        clock = new MutableClock();
        containers = mock(AutonomousDatabaseContainerManager.class);
        lenient().when(containers.containerName(any())).thenAnswer(call -> "floci-oci-adb-" + call.getArgument(0));
        lenient().when(containers.clientEndpoint(any())).thenReturn(new EndpointInfo("localhost", 11530));
        lenient().when(containers.tnsnames(any())).thenReturn(TNSNAMES);
        lenient().when(containers.health(any())).thenReturn(Health.STARTING);
        service = service(Runnable::run);
    }

    private AutonomousDatabaseService service(Executor lifecycle) {
        return new AutonomousDatabaseService(databases, config, null, workRequests, OciContext.fromConfig(config),
                NetworkReferences.NONE, clock, containers, lifecycle);
    }

    private static Map<String, Object> details() {
        Map<String, Object> details = new HashMap<>();
        details.put("compartmentId", COMPARTMENT);
        details.put("dbName", "orders");
        details.put("adminPassword", PASSWORD);
        return details;
    }

    private StoredWorkRequest workRequest(String id) {
        return workRequests.get(AutonomousDatabaseService.SERVICE, id);
    }

    private StoredAutonomousDatabase available() {
        AutonomousDatabaseService.Result created = service.create(details());
        when(containers.health(any())).thenReturn(Health.HEALTHY);
        service.pollReadiness();
        return service.get(created.database().getId());
    }

    // ── Create ─────────────────────────────────────────────────────────────────

    @Test
    void createIsProvisioningUntilTheContainerIsHealthy() {
        AutonomousDatabaseService.Result created = service.create(details());

        StoredAutonomousDatabase database = created.database();
        assertEquals("PROVISIONING", database.getLifecycleState());
        assertEquals("IN_PROGRESS", workRequest(created.workRequestId()).getStatus());
        assertEquals("CREATED", workRequest(created.workRequestId()).getResources().getFirst().getActionType());
        verify(containers).create(database, PASSWORD);
        assertFalse(database.toWire().containsKey("connectionStrings"), "nothing listens yet");
        assertFalse(database.getAttributes().containsValue(PASSWORD));

        service.pollReadiness();
        assertEquals("PROVISIONING", service.get(database.getId()).getLifecycleState());
    }

    @Test
    void aHealthyContainerMakesTheDatabaseAvailableWithItsOwnConnectionStrings() {
        AutonomousDatabaseService.Result created = service.create(details());
        when(containers.health(any())).thenReturn(Health.HEALTHY);

        service.pollReadiness();

        StoredAutonomousDatabase database = service.get(created.database().getId());
        assertEquals("AVAILABLE", database.getLifecycleState());
        assertNull(database.getWorkRequestId());
        StoredWorkRequest workRequest = workRequest(created.workRequestId());
        assertEquals("SUCCEEDED", workRequest.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> strings = (Map<String, Object>) database.toWire().get("connectionStrings");
        assertEquals("localhost:11530/orders_high.adb.oraclecloud.com", strings.get("high"));
        assertEquals("localhost:11530/orders_low.adb.oraclecloud.com", strings.get("low"));
        assertNull(strings.get("medium"), "the strings follow the container's file, not a guess");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) strings.get("profiles");
        assertEquals(2, profiles.size());
        assertTrue(profiles.getFirst().get("value").toString().contains("(port=11530)(host=localhost)"));
    }

    @Test
    void aFirstBootThatExitsIsUnavailableWithTheContainerLogAndRemovesTheContainer() {
        AutonomousDatabaseService.Result created = service.create(details());
        when(containers.health(any())).thenReturn(Health.EXITED);
        when(containers.logTail(any(), anyInt())).thenReturn("The ADMIN_PASSWORD must be between 12 and 30");

        service.pollReadiness();

        StoredAutonomousDatabase database = service.get(created.database().getId());
        assertEquals("UNAVAILABLE", database.getLifecycleState());
        assertTrue(database.getLifecycleDetails().contains("The ADMIN_PASSWORD must be"), database.getLifecycleDetails());
        assertEquals("FAILED", workRequest(created.workRequestId()).getStatus());
        verify(containers).remove(database);
    }

    @Test
    void aContainerThatIsNeverHealthyTimesOut() {
        AutonomousDatabaseService.Result created = service.create(details());
        clock.advance(Duration.ofSeconds(599));
        service.pollReadiness();
        assertEquals("PROVISIONING", service.get(created.database().getId()).getLifecycleState());

        clock.advance(Duration.ofSeconds(2));
        service.pollReadiness();

        StoredAutonomousDatabase database = service.get(created.database().getId());
        assertEquals("UNAVAILABLE", database.getLifecycleState());
        assertTrue(database.getLifecycleDetails().contains("600 seconds"), database.getLifecycleDetails());
        assertEquals("FAILED", workRequest(created.workRequestId()).getStatus());
    }

    @Test
    void aFailedImagePullIsUnavailable() {
        doThrow(new IllegalStateException("pull timed out")).when(containers).create(any(), any());

        AutonomousDatabaseService.Result created = service.create(details());

        StoredAutonomousDatabase database = service.get(created.database().getId());
        assertEquals("UNAVAILABLE", database.getLifecycleState());
        assertTrue(database.getLifecycleDetails().contains("pull timed out"));
        assertEquals("FAILED", workRequest(created.workRequestId()).getStatus());
    }

    @Test
    void aVaultSecretInsteadOfAnAdminPasswordIsNotImplemented() {
        Map<String, Object> details = details();
        details.remove("adminPassword");
        details.put("secretId", "ocid1.vaultsecret.oc1.iad.s");

        OciException e = assertThrows(OciException.class, () -> service.create(details));

        assertEquals(501, e.getHttpStatus());
        verify(containers, never()).create(any(), any());
    }

    // ── Stop, start, restart ───────────────────────────────────────────────────

    @Test
    void stopAndStartUseTheSameContainer() {
        StoredAutonomousDatabase database = available();

        AutonomousDatabaseService.Result stopped = service.stop(database.getId(), null);
        assertEquals("STOPPED", service.get(database.getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(stopped.workRequestId()).getStatus());
        verify(containers).stop(database);

        when(containers.health(any())).thenReturn(Health.STARTING);
        AutonomousDatabaseService.Result started = service.start(database.getId(), null);
        assertEquals("STARTING", started.database().getLifecycleState());
        assertEquals("IN_PROGRESS", workRequest(started.workRequestId()).getStatus());
        verify(containers).start(database);

        when(containers.health(any())).thenReturn(Health.HEALTHY);
        service.pollReadiness();
        assertEquals("AVAILABLE", service.get(database.getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(started.workRequestId()).getStatus());
        verify(containers, times(1)).create(any(), any());
    }

    @Test
    void restartWaitsForTheContainerToBeHealthyAgain() {
        StoredAutonomousDatabase database = available();
        when(containers.health(any())).thenReturn(Health.STARTING);

        AutonomousDatabaseService.Result restarted = service.restart(database.getId(), null);

        assertEquals("RESTARTING", service.get(database.getId()).getLifecycleState());
        verify(containers).restart(database);
        when(containers.health(any())).thenReturn(Health.HEALTHY);
        service.pollReadiness();
        assertEquals("AVAILABLE", service.get(database.getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(restarted.workRequestId()).getStatus());
    }

    @Test
    void aStartWhoseContainerIsGoneIsUnavailableAndNeverRecreatesIt() {
        StoredAutonomousDatabase database = available();
        service.stop(database.getId(), null);
        doThrow(new IllegalStateException(AutonomousDatabaseService.CONTAINER_GONE)).when(containers).start(any());

        AutonomousDatabaseService.Result started = service.start(database.getId(), null);

        StoredAutonomousDatabase after = service.get(database.getId());
        assertEquals("UNAVAILABLE", after.getLifecycleState());
        assertTrue(after.getLifecycleDetails().contains(AutonomousDatabaseService.CONTAINER_GONE));
        assertEquals("FAILED", workRequest(started.workRequestId()).getStatus());
        verify(containers, times(1)).create(any(), any());
    }

    @Test
    void anOperationUnderWayRefusesAnotherWithIncorrectState() {
        List<Runnable> queued = new ArrayList<>();
        AutonomousDatabaseService deferred = service(queued::add);
        StoredAutonomousDatabase database = available();

        deferred.stop(database.getId(), null);

        OciException e = assertThrows(OciException.class, () -> deferred.start(database.getId(), null));
        assertEquals("IncorrectState", e.getCode());
        assertEquals(409, e.getHttpStatus());
    }

    // ── Update, wallet, delete ─────────────────────────────────────────────────

    @Test
    void theContainerKeepsItsNameAndAdminPassword() {
        StoredAutonomousDatabase database = available();

        assertEquals(501, assertThrows(OciException.class,
                () -> service.update(database.getId(), Map.of("dbName", "billing"), null)).getHttpStatus());
        assertEquals(501, assertThrows(OciException.class,
                () -> service.update(database.getId(), Map.of("adminPassword", "Another12345#"), null)).getHttpStatus());
        assertEquals("renamed", service.update(database.getId(), Map.of("displayName", "renamed"), null)
                .database().getDisplayName());
        assertNotNull(service.get(database.getId()).getAttributes().get("connectionStrings"),
                "an update keeps the container's connection strings");
    }

    @Test
    void theWalletComesFromTheContainerOnceTheDatabaseHasBeenAvailable() {
        AutonomousDatabaseService.Result created = service.create(details());
        OciException early = assertThrows(OciException.class, () -> service.generateWallet(
                created.database().getId(), Map.of("password", "Wallet#Pass1")));
        assertEquals("IncorrectState", early.getCode());

        when(containers.health(any())).thenReturn(Health.HEALTHY);
        service.pollReadiness();
        when(containers.wallet(any(), eq("Wallet#Pass1"))).thenReturn(new byte[]{80, 75});

        assertArrayEquals(new byte[]{80, 75},
                service.generateWallet(created.database().getId(), Map.of("password", "Wallet#Pass1")));
    }

    @Test
    void deleteRemovesTheContainer() {
        StoredAutonomousDatabase database = available();

        String workRequestId = service.delete(database.getId(), null);

        assertEquals("TERMINATED", service.get(database.getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(workRequestId).getStatus());
        assertEquals("DELETED", workRequest(workRequestId).getResources().getFirst().getActionType());
        verify(containers).remove(database);
    }

    @Test
    void aDeleteDockerRefusesLeavesTheDatabaseUnavailableAndDeletableAgain() {
        StoredAutonomousDatabase database = available();
        doThrow(new IllegalStateException("Docker is unreachable")).when(containers).remove(any());

        String failed = service.delete(database.getId(), null);

        StoredAutonomousDatabase after = service.get(database.getId());
        assertEquals("UNAVAILABLE", after.getLifecycleState());
        assertEquals("The container could not be removed: Docker is unreachable", after.getLifecycleDetails());
        assertEquals("FAILED", workRequest(failed).getStatus());
        assertNull(after.getWorkRequestId());

        doNothing().when(containers).remove(any());
        String retried = service.delete(database.getId(), null);

        assertEquals("TERMINATED", service.get(database.getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(retried).getStatus());
    }

    @Test
    void adoptionSavesTheHostPortTheContainerPublishes() {
        StoredAutonomousDatabase database = available();
        databases.get(database.getId()).orElseThrow().setHostPort(0);
        when(containers.adopt(any())).thenAnswer(call -> {
            call.<StoredAutonomousDatabase>getArgument(0).setHostPort(11544);
            return true;
        });

        service(Runnable::run).adoptPersistedDatabases();

        assertEquals(11544, databases.get(database.getId()).orElseThrow().getHostPort());
    }

    @Test
    void deleteDuringProvisioningCancelsTheCreate() {
        List<Runnable> queued = new ArrayList<>();
        AutonomousDatabaseService deferred = service(queued::add);
        AutonomousDatabaseService.Result created = deferred.create(details());

        String deleted = deferred.delete(created.database().getId(), null);
        queued.forEach(Runnable::run);

        assertEquals("CANCELED", workRequest(created.workRequestId()).getStatus());
        assertEquals("SUCCEEDED", workRequest(deleted).getStatus());
        assertEquals("TERMINATED", deferred.get(created.database().getId()).getLifecycleState());
        verify(containers, never()).create(any(), any());
        verify(containers).remove(any());
    }

    @Test
    void aResetRemovesEveryContainer() {
        StoredAutonomousDatabase database = available();

        service.clear();

        verify(containers).remove(database);
        assertTrue(databases.scan(k -> true).isEmpty());
    }

    // ── After a restart of floci-oci ───────────────────────────────────────────

    @Test
    void anAvailableDatabaseAdoptsItsContainerAndStartsItAgain() {
        StoredAutonomousDatabase database = available();
        when(containers.adopt(any())).thenReturn(true);
        when(containers.health(any())).thenReturn(Health.STARTING);
        AutonomousDatabaseService restarted = service(Runnable::run);

        restarted.adoptPersistedDatabases();

        assertEquals("STARTING", restarted.get(database.getId()).getLifecycleState());
        verify(containers).start(any());
        when(containers.health(any())).thenReturn(Health.HEALTHY);
        restarted.pollReadiness();
        assertEquals("AVAILABLE", restarted.get(database.getId()).getLifecycleState());
        verify(containers, times(1)).create(any(), any());
    }

    @Test
    void aDatabaseWhoseContainerIsGoneIsUnavailableRatherThanRecreated() {
        StoredAutonomousDatabase database = available();
        when(containers.adopt(any())).thenReturn(false);

        service(Runnable::run).adoptPersistedDatabases();

        StoredAutonomousDatabase after = databases.get(database.getId()).orElseThrow();
        assertEquals("UNAVAILABLE", after.getLifecycleState());
        assertEquals(AutonomousDatabaseService.CONTAINER_GONE, after.getLifecycleDetails());
        verify(containers, times(1)).create(any(), any());
        verify(containers, never()).start(any());
    }

    @Test
    void aStoppedDatabaseStaysStoppedWithItsContainer() {
        StoredAutonomousDatabase database = available();
        service.stop(database.getId(), null);
        when(containers.adopt(any())).thenReturn(true);

        service(Runnable::run).adoptPersistedDatabases();

        assertEquals("STOPPED", databases.get(database.getId()).orElseThrow().getLifecycleState());
        verify(containers, never()).start(any());
    }

    @Test
    void aCreateInterruptedWithItsContainerRunningCarriesOnUnderItsWorkRequest() {
        AutonomousDatabaseService.Result created = service.create(details());
        when(containers.adopt(any())).thenReturn(true);
        AutonomousDatabaseService restarted = service(Runnable::run);

        restarted.adoptPersistedDatabases();
        when(containers.health(any())).thenReturn(Health.HEALTHY);
        restarted.pollReadiness();

        assertEquals("AVAILABLE", restarted.get(created.database().getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(created.workRequestId()).getStatus());
    }

    @Test
    void aCreateInterruptedBeforeItsContainerExistedFails() {
        List<Runnable> queued = new ArrayList<>();
        AutonomousDatabaseService.Result created = service(queued::add).create(details());
        when(containers.adopt(any())).thenReturn(false);

        service(Runnable::run).adoptPersistedDatabases();

        StoredAutonomousDatabase after = databases.get(created.database().getId()).orElseThrow();
        assertEquals("UNAVAILABLE", after.getLifecycleState());
        assertEquals(AutonomousDatabaseService.INTERRUPTED, after.getLifecycleDetails());
        assertEquals("FAILED", workRequest(created.workRequestId()).getStatus());
    }

    @Test
    void aDeleteInterruptedByTheRestartCompletes() {
        List<Runnable> queued = new ArrayList<>();
        StoredAutonomousDatabase database = available();
        String deleted = service(queued::add).delete(database.getId(), null);
        when(containers.adopt(any())).thenReturn(false);

        service(Runnable::run).adoptPersistedDatabases();

        assertEquals("TERMINATED", databases.get(database.getId()).orElseThrow().getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(deleted).getStatus());
    }
}
