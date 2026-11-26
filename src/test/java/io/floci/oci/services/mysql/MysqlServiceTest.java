package io.floci.oci.services.mysql;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.NetworkReferences;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.mysql.model.StoredMysqlConfiguration;
import io.floci.oci.services.mysql.model.StoredMysqlDbSystem;
import io.floci.oci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MysqlServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..mysqltesttenancy";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..mysqltestcompartment";
    private static final String SUBNET = "ocid1.subnet.oc1.iad.mysqltestsubnet";
    private static final String MISSING_SUBNET = "ocid1.subnet.oc1.iad.missing";

    private EmulatorConfig config;
    private InMemoryStorage<String, StoredMysqlDbSystem> systems;
    private WorkRequestService workRequests;
    private MysqlContainerManager containers;
    private MysqlConfigurationsService configurations;
    private MutableClock clock;
    private MysqlService mock;
    private MysqlService real;

    /** Knows {@link #SUBNET} and nothing else, like Networking with one subnet. */
    private static final NetworkReferences ONE_SUBNET = new NetworkReferences() {
        @Override
        public void requireVcn(String vcnId) {
        }

        @Override
        public void requireSubnets(Collection<String> subnetIds) {
            for (String id : subnetIds) {
                if (!SUBNET.equals(id)) {
                    throw OciException.relatedResourceNotAuthorizedOrNotFound(
                            "Authorization failed or requested resource not found: subnet " + id);
                }
            }
        }

        @Override
        public void requireNsgs(Collection<String> nsgIds) {
            if (nsgIds != null && !nsgIds.isEmpty()) {
                throw OciException.relatedResourceNotAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: networksecuritygroup "
                                + nsgIds.iterator().next());
            }
        }
    };

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.hostname()).thenReturn(Optional.empty());
        lenient().when(config.services().mysql().basePort()).thenReturn(13306);
        lenient().when(config.services().mysql().readyTimeoutSeconds()).thenReturn(180);
        systems = new InMemoryStorage<>();
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        clock = new MutableClock();
        containers = mock(MysqlContainerManager.class);
        lenient().when(containers.containerName(any())).thenAnswer(call -> "floci-oci-mysql-" + call.getArgument(0));
        lenient().when(containers.volumeName(any())).thenAnswer(call -> "floci-oci-mysql-vol-" + call.getArgument(0));
        lenient().when(containers.clientEndpoint(any())).thenReturn(new EndpointInfo("localhost", 13306));
        lenient().when(containers.isRunning(any())).thenReturn(true);
        configurations = new MysqlConfigurationsService(new InMemoryStorage<>(), OciContext.fromConfig(config), clock);

        // Mock mode has no manager at all; real mode runs its Docker work inline.
        mock = service(null);
        real = service(containers);
    }

    private MysqlService service(MysqlContainerManager manager) {
        return new MysqlService(systems, config, null, workRequests, manager, configurations,
                OciContext.fromConfig(config), ONE_SUBNET, clock, Runnable::run);
    }

    private static Map<String, Object> createDetails() {
        Map<String, Object> details = new HashMap<>();
        details.put("compartmentId", COMPARTMENT);
        details.put("shapeName", "MySQL.2");
        details.put("subnetId", SUBNET);
        details.put("displayName", "orders-db");
        details.put("adminUsername", "adminUser");
        details.put("adminPassword", "BEstrO0ng_#11");
        details.put("availabilityDomain", "Floc:US-ASHBURN-AD-1");
        return details;
    }

    private StoredWorkRequest workRequest(String id) {
        return workRequests.get(MysqlService.WR_SERVICE, id);
    }

    private StoredMysqlDbSystem activeRealSystem() {
        MysqlService.Created created = real.createDbSystem(createDetails());
        when(containers.probeReady(any())).thenReturn(true);
        real.pollReadiness();
        return real.getDbSystem(created.system().getId());
    }

    // ── Create, in mock mode ───────────────────────────────────────────────────

    @Test
    void createInMockModeIsActiveAtOnceWithEveryMandatoryField() {
        MysqlService.Created created = mock.createDbSystem(createDetails());
        Map<String, Object> wire = created.system().toWire();

        assertEquals("ACTIVE", wire.get("lifecycleState"));
        assertTrue(((String) wire.get("id")).startsWith("ocid1.mysqldbsystem.oc1.iad."));
        for (String field : List.of("id", "displayName", "compartmentId", "subnetId", "mysqlVersion",
                "dataStorageSizeInGBs", "dataStorage", "lifecycleState", "maintenance", "deletionPolicy",
                "timeCreated", "timeUpdated", "databaseMode", "accessMode")) {
            assertNotNull(wire.get(field), field);
        }
        assertEquals(MysqlCatalog.DEFAULT_VERSION, wire.get("mysqlVersion"));
        assertEquals(3306, wire.get("port"));
        assertEquals(33060, wire.get("portX"));
        assertEquals(50, wire.get("dataStorageSizeInGBs"));
        assertEquals(Map.of("sourceType", "NONE"), wire.get("source"));
        assertEquals(Map.of("windowStartTime", "SUN 00:00"), wire.get("maintenance"));
        assertEquals("Floc:US-ASHBURN-AD-1", wire.get("availabilityDomain"));
        assertEquals("FAULT-DOMAIN-1", wire.get("faultDomain"));
        assertFalse(wire.containsKey("adminUsername"));
        assertFalse(wire.containsKey("adminPassword"));
        assertEquals(MysqlCatalog.defaultConfigurationId("MySQL.2.Standalone", "oc1"), wire.get("configurationId"));
        assertEquals("SUCCEEDED", workRequest(created.workRequestId()).getStatus());
        assertEquals("CREATE_DBSYSTEM", workRequest(created.workRequestId()).getOperationType());
    }

    @Test
    void theRequestedVersionWindowAndPortsComeBackExactly() {
        Map<String, Object> details = createDetails();
        details.put("mysqlVersion", "8.0");
        details.put("maintenance", Map.of("windowStartTime", "sun 01:00"));
        details.put("port", 3307);

        Map<String, Object> wire = mock.createDbSystem(details).system().toWire();

        // A family resolves to its newest version, which still has the two parts Terraform compares.
        assertEquals("8.0.41", wire.get("mysqlVersion"));
        assertEquals(Map.of("windowStartTime", "sun 01:00"), wire.get("maintenance"));
        assertEquals(3307, wire.get("port"));
    }

    @Test
    void createValidatesShapeVersionSourceAndNetwork() {
        Map<String, Object> badShape = createDetails();
        badShape.put("shapeName", "MySQL.3");
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> mock.createDbSystem(badShape)).getCode());

        Map<String, Object> badVersion = createDetails();
        badVersion.put("mysqlVersion", "5.7.44");
        assertEquals("InvalidParameter",
                assertThrows(OciException.class, () -> mock.createDbSystem(badVersion)).getCode());

        Map<String, Object> fromBackup = createDetails();
        fromBackup.put("source", Map.of("sourceType", "BACKUP", "backupId", "ocid1.mysqlbackup.oc1.iad.x"));
        assertEquals("InvalidParameter",
                assertThrows(OciException.class, () -> mock.createDbSystem(fromBackup)).getCode());

        Map<String, Object> unknownSubnet = createDetails();
        unknownSubnet.put("subnetId", MISSING_SUBNET);
        OciException error = assertThrows(OciException.class, () -> mock.createDbSystem(unknownSubnet));
        assertEquals("RelatedResourceNotAuthorizedOrNotFound", error.getCode());
        assertEquals(400, error.getHttpStatus());

        Map<String, Object> unknownNsg = createDetails();
        unknownNsg.put("nsgIds", List.of("ocid1.networksecuritygroup.oc1.iad.missing"));
        assertEquals("RelatedResourceNotAuthorizedOrNotFound",
                assertThrows(OciException.class, () -> mock.createDbSystem(unknownNsg)).getCode());

        Map<String, Object> noSubnet = createDetails();
        noSubnet.remove("subnetId");
        assertEquals("MissingParameter", assertThrows(OciException.class, () -> mock.createDbSystem(noSubnet)).getCode());
        assertTrue(systems.keys().isEmpty());
    }

    @Test
    void realModeNeedsTheAdminCredentialsAndMockModeDoesNot() {
        Map<String, Object> details = createDetails();
        details.remove("adminPassword");

        assertEquals("MissingParameter", assertThrows(OciException.class, () -> real.createDbSystem(details)).getCode());
        assertEquals("ACTIVE", mock.createDbSystem(details).system().getLifecycleState());
    }

    @Test
    void aConfigurationMustExistAndFitTheShape() {
        Map<String, Object> unknown = createDetails();
        unknown.put("configurationId", "ocid1.mysqlconfiguration.oc1.iad.missing");
        assertEquals("RelatedResourceNotAuthorizedOrNotFound",
                assertThrows(OciException.class, () -> mock.createDbSystem(unknown)).getCode());

        Map<String, Object> otherShape = createDetails();
        otherShape.put("configurationId", MysqlCatalog.defaultConfigurationId("MySQL.4.Standalone", "oc1"));
        assertEquals("InvalidParameter",
                assertThrows(OciException.class, () -> mock.createDbSystem(otherShape)).getCode());

        StoredMysqlConfiguration custom = configurations.createConfiguration(Map.of("compartmentId", COMPARTMENT,
                "shapeName", "MySQL.2", "displayName", "tuned", "variables", Map.of("maxConnections", 500)));
        Map<String, Object> withCustom = createDetails();
        withCustom.put("configurationId", custom.getId());
        assertEquals(custom.getId(), mock.createDbSystem(withCustom).system().getConfigurationId());
    }

    // ── Real mode lifecycle ────────────────────────────────────────────────────

    @Test
    void realCreateStartsTheContainerAndBecomesActiveOnceMysqlAnswers() {
        MysqlService.Created created = real.createDbSystem(createDetails());

        assertEquals("CREATING", created.system().getLifecycleState());
        assertEquals("IN_PROGRESS", workRequest(created.workRequestId()).getStatus());
        verify(containers).start(any(), eq("BEstrO0ng_#11"));

        when(containers.probeReady(any())).thenReturn(true);
        real.pollReadiness();

        StoredMysqlDbSystem system = real.getDbSystem(created.system().getId());
        assertEquals("ACTIVE", system.getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(created.workRequestId()).getStatus());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) system.toWire().get("endpoints");
        assertEquals("localhost", endpoints.getFirst().get("hostname"));
        assertEquals(13306, endpoints.getFirst().get("port"));
        assertEquals("ACTIVE", endpoints.getFirst().get("status"));
    }

    @Test
    void aContainerThatExitsLeavesTheSystemFailedNotNeedingAttention() {
        MysqlService.Created created = real.createDbSystem(createDetails());
        when(containers.isRunning(any())).thenReturn(false);

        real.pollReadiness();

        StoredMysqlDbSystem system = real.getDbSystem(created.system().getId());
        assertEquals("FAILED", system.getLifecycleState());
        assertNotNull(system.getLifecycleDetails());
        assertEquals("FAILED", workRequest(created.workRequestId()).getStatus());
        assertEquals("InternalServerError", workRequest(created.workRequestId()).getErrors().getFirst().getCode());
    }

    @Test
    void aStartThatTimesOutFails() {
        MysqlService.Created created = real.createDbSystem(createDetails());
        clock.advance(Duration.ofSeconds(181));

        real.pollReadiness();

        assertEquals("FAILED", real.getDbSystem(created.system().getId()).getLifecycleState());
    }

    @Test
    void aStartThatThrowsFailsTheCreate() {
        doThrow(new IllegalStateException("no such image")).when(containers).start(any(), any());

        MysqlService.Created created = real.createDbSystem(createDetails());

        assertEquals("FAILED", real.getDbSystem(created.system().getId()).getLifecycleState());
        assertTrue(workRequest(created.workRequestId()).getErrors().getFirst().getMessage().contains("no such image"));
    }

    @Test
    void aFailedDeleteProtectedSystemCanBeUnprotectedAndDeleted() {
        doThrow(new IllegalStateException("no such image")).when(containers).start(any(), any());
        Map<String, Object> details = createDetails();
        details.put("deletionPolicy", Map.of("isDeleteProtected", true));
        String id = real.createDbSystem(details).system().getId();
        assertEquals("FAILED", real.getDbSystem(id).getLifecycleState());

        OciException refused = assertThrows(OciException.class,
                () -> real.updateDbSystem(id, Map.of("dataStorageSizeInGBs", 100), null));
        assertEquals(409, refused.getHttpStatus());
        assertEquals("IncorrectState", refused.getCode());

        real.updateDbSystem(id, Map.of("deletionPolicy", Map.of("isDeleteProtected", false)), null);
        assertEquals("FAILED", real.getDbSystem(id).getLifecycleState());
        real.deleteDbSystem(id, null);
        assertEquals("DELETED", real.getDbSystem(id).getLifecycleState());
    }

    @Test
    void stopAndStartKeepTheSystemAndNeedAShutdownType() {
        StoredMysqlDbSystem system = activeRealSystem();

        assertEquals("MissingParameter", assertThrows(OciException.class,
                () -> real.stopDbSystem(system.getId(), Map.of(), null)).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> real.stopDbSystem(system.getId(), Map.of("shutdownType", "GENTLE"), null)).getCode());

        String stop = real.stopDbSystem(system.getId(), Map.of("shutdownType", "FAST"), null);
        assertEquals("INACTIVE", real.getDbSystem(system.getId()).getLifecycleState());
        assertEquals("STOP_DBSYSTEM", workRequest(stop).getOperationType());
        verify(containers, never()).remove(any());

        String start = real.startDbSystem(system.getId(), null);
        assertEquals("UPDATING", real.getDbSystem(system.getId()).getLifecycleState());
        real.pollReadiness();
        assertEquals("ACTIVE", real.getDbSystem(system.getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(start).getStatus());
        // A start does not carry a password: the data volume already has the admin.
        verify(containers).start(any(), isNull());
    }

    @Test
    void anInactiveSystemCannotBeStoppedOrRestarted() {
        StoredMysqlDbSystem system = activeRealSystem();
        real.stopDbSystem(system.getId(), Map.of("shutdownType", "IMMEDIATE"), null);

        OciException error = assertThrows(OciException.class,
                () -> real.restartDbSystem(system.getId(), Map.of("shutdownType", "FAST"), null));
        assertEquals("IncorrectState", error.getCode());
        assertEquals(409, error.getHttpStatus());
    }

    @Test
    void aSecondMutationWhileTheFirstIsQueuedIsRefusedAndTheFirstStillFinishes() {
        StoredMysqlDbSystem system = activeRealSystem();
        List<Runnable> queued = new ArrayList<>();
        MysqlService deferred = new MysqlService(systems, config, null, workRequests, containers, configurations,
                OciContext.fromConfig(config), ONE_SUBNET, clock, queued::add);

        String stop = deferred.stopDbSystem(system.getId(), Map.of("shutdownType", "FAST"), null);

        OciException error = assertThrows(OciException.class,
                () -> deferred.restartDbSystem(system.getId(), Map.of("shutdownType", "FAST"), null));
        assertEquals("IncorrectState", error.getCode());
        assertEquals(409, error.getHttpStatus());
        assertEquals("DB system " + system.getId() + " is UPDATING and cannot be changed", error.getMessage());
        assertEquals("IncorrectState", assertThrows(OciException.class,
                () -> deferred.updateDbSystem(system.getId(), Map.of("mysqlVersion", "8.0.41"), null)).getCode());
        assertEquals(1, queued.size());

        queued.removeFirst().run();

        assertEquals("INACTIVE", deferred.getDbSystem(system.getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(stop).getStatus());
    }

    @Test
    void racingMutationsAcceptOneAndEveryAcceptedWorkRequestFinishes() throws Exception {
        StoredMysqlDbSystem system = activeRealSystem();
        List<Runnable> queued = Collections.synchronizedList(new ArrayList<>());
        MysqlService deferred = new MysqlService(systems, config, null, workRequests, containers, configurations,
                OciContext.fromConfig(config), ONE_SUBNET, clock, queued::add);
        int callers = 8;
        CountDownLatch go = new CountDownLatch(1);
        List<String> accepted = Collections.synchronizedList(new ArrayList<>());
        List<String> refusals = Collections.synchronizedList(new ArrayList<>());
        ExecutorService threads = Executors.newFixedThreadPool(callers);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                boolean stop = i % 2 == 0;
                calls.add(threads.submit(() -> {
                    go.await();
                    try {
                        accepted.add(stop
                                ? deferred.stopDbSystem(system.getId(), Map.of("shutdownType", "FAST"), null)
                                : deferred.restartDbSystem(system.getId(), Map.of("shutdownType", "FAST"), null));
                    } catch (OciException e) {
                        refusals.add(e.getHttpStatus() + " " + e.getCode());
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> call : calls) {
                call.get(10, TimeUnit.SECONDS);
            }
        } finally {
            threads.shutdownNow();
        }

        assertEquals(1, accepted.size());
        assertEquals(callers - 1, refusals.size());
        assertTrue(refusals.stream().allMatch("409 IncorrectState"::equals), refusals.toString());

        for (Runnable task : List.copyOf(queued)) {
            task.run();
        }
        deferred.pollReadiness();

        for (String workRequestId : accepted) {
            assertEquals("SUCCEEDED", workRequest(workRequestId).getStatus());
        }
    }

    @Test
    void aDeleteCancelsAQueuedStopAndBothWorkRequestsFinish() {
        StoredMysqlDbSystem system = activeRealSystem();
        List<Runnable> queued = new ArrayList<>();
        MysqlService deferred = new MysqlService(systems, config, null, workRequests, containers, configurations,
                OciContext.fromConfig(config), ONE_SUBNET, clock, queued::add);

        String stop = deferred.stopDbSystem(system.getId(), Map.of("shutdownType", "FAST"), null);
        String delete = deferred.deleteDbSystem(system.getId(), null);
        for (Runnable task : List.copyOf(queued)) {
            task.run();
        }

        assertEquals("CANCELED", workRequest(stop).getStatus());
        assertEquals("SUCCEEDED", workRequest(delete).getStatus());
        assertEquals("DELETED", deferred.getDbSystem(system.getId()).getLifecycleState());
        // Only the create stopped a container: the canceled stop's step was skipped.
        verify(containers, times(1)).stop(any());
    }

    @Test
    void deleteRemovesTheContainerAndKeepsTheSystemReadableAsDeleted() {
        StoredMysqlDbSystem system = activeRealSystem();

        String workRequestId = real.deleteDbSystem(system.getId(), null);

        assertEquals("DELETED", real.getDbSystem(system.getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(workRequestId).getStatus());
        verify(containers).remove(any());
        assertEquals("IncorrectState", assertThrows(OciException.class,
                () -> real.deleteDbSystem(system.getId(), null)).getCode());
    }

    @Test
    void aDeleteProtectedSystemCannotBeDeleted() {
        Map<String, Object> details = createDetails();
        details.put("deletionPolicy", Map.of("isDeleteProtected", true));
        StoredMysqlDbSystem system = mock.createDbSystem(details).system();

        assertEquals(409, assertThrows(OciException.class,
                () -> mock.deleteDbSystem(system.getId(), null)).getHttpStatus());

        mock.updateDbSystem(system.getId(), Map.of("deletionPolicy", Map.of("isDeleteProtected", false)), null);
        mock.deleteDbSystem(system.getId(), null);
        assertEquals("DELETED", mock.getDbSystem(system.getId()).getLifecycleState());
    }

    @Test
    void aStaleEtagIsRejected() {
        StoredMysqlDbSystem system = mock.createDbSystem(createDetails()).system();

        assertEquals(412, assertThrows(OciException.class,
                () -> mock.updateDbSystem(system.getId(), Map.of("displayName", "x"), "\"stale\"")).getHttpStatus());
    }

    // ── Update ─────────────────────────────────────────────────────────────────

    @Test
    void updateIsMetadataOnlyAndFinishesAtOnce() {
        StoredMysqlDbSystem system = activeRealSystem();

        String workRequestId = real.updateDbSystem(system.getId(), Map.of("displayName", "renamed",
                "dataStorageSizeInGBs", 100, "backupPolicy", Map.of("retentionInDays", 14)), null);

        StoredMysqlDbSystem updated = real.getDbSystem(system.getId());
        assertEquals("renamed", updated.getDisplayName());
        assertEquals(100, updated.getDataStorageSizeInGBs());
        assertEquals(100, updated.getDataStorage().get("dataStorageSizeInGBs"));
        assertEquals(14, updated.getBackupPolicy().get("retentionInDays"));
        assertEquals(true, updated.getBackupPolicy().get("isEnabled"));
        assertEquals("ACTIVE", updated.getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(workRequestId).getStatus());
    }

    @Test
    void updateRejectsShrinkingStorageAndAnUnknownSubnetWithoutChangingAnything() {
        StoredMysqlDbSystem system = mock.createDbSystem(createDetails()).system();

        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> mock.updateDbSystem(system.getId(),
                Map.of("displayName", "renamed", "dataStorageSizeInGBs", 10), null)).getCode());
        assertEquals("RelatedResourceNotAuthorizedOrNotFound", assertThrows(OciException.class,
                () -> mock.updateDbSystem(system.getId(), Map.of("displayName", "renamed",
                        "subnetId", MISSING_SUBNET), null)).getCode());
        assertEquals("orders-db", mock.getDbSystem(system.getId()).getDisplayName());
    }

    @Test
    void aNewVersionRestartsTheServerOnTheNewImage() {
        StoredMysqlDbSystem system = activeRealSystem();

        String workRequestId = real.updateDbSystem(system.getId(), Map.of("mysqlVersion", "9.2.0"), null);

        assertEquals("UPDATING", real.getDbSystem(system.getId()).getLifecycleState());
        assertEquals("9.2.0", real.getDbSystem(system.getId()).getMysqlVersion());
        real.pollReadiness();
        assertEquals("ACTIVE", real.getDbSystem(system.getId()).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(workRequestId).getStatus());
    }

    // ── Lists, configurations and reset ────────────────────────────────────────

    @Test
    void listFiltersAndSorts() {
        mock.createDbSystem(createDetails());
        Map<String, Object> second = createDetails();
        second.put("displayName", "analytics-db");
        clock.advance(Duration.ofSeconds(1));
        mock.createDbSystem(second);

        assertEquals(List.of("analytics-db", "orders-db"), mock.listDbSystems(COMPARTMENT, null, null, null, null,
                null, List.of(), null, null).stream().map(StoredMysqlDbSystem::getDisplayName).toList());
        assertEquals(List.of("analytics-db", "orders-db"), mock.listDbSystems(COMPARTMENT, null, null, null, null,
                null, List.of(), "displayName", null).stream().map(StoredMysqlDbSystem::getDisplayName).toList());
        assertEquals(1, mock.listDbSystems(COMPARTMENT, null, null, "orders-db", null, null, List.of(), null, null)
                .size());
        assertTrue(mock.listDbSystems(COMPARTMENT, true, null, null, null, null, List.of(), null, null).isEmpty());
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> mock.listDbSystems(COMPARTMENT,
                null, null, null, "SLEEPING", null, List.of(), null, null)).getCode());
    }

    @Test
    void aConfigurationKeepsTheOptionsItWasCreatedWith() {
        List<Map<String, String>> options = List.of(Map.of("name", "max_connections", "value", "500"),
                Map.of("name", "sql_mode", "value", ""), Map.of("name", "skip_name_resolve"));
        StoredMysqlConfiguration custom = configurations.createConfiguration(Map.of("compartmentId", COMPARTMENT,
                "shapeName", "MySQL.2", "options", options));

        assertEquals(options, configurations.configurationWire(custom.getId()).get("options"));
        Map<String, Object> summary = configurations.listConfigurations(COMPARTMENT, custom.getId(), null,
                List.of("CUSTOM"), null, null, null, null).getFirst();
        assertFalse(summary.containsKey("options"));

        StoredMysqlConfiguration plain = configurations.createConfiguration(Map.of("compartmentId", COMPARTMENT,
                "shapeName", "MySQL.2"));
        assertEquals(List.of(), configurations.configurationWire(plain.getId()).get("options"));
    }

    @Test
    void configurationOptionsNeedANameAndAStringValue() {
        for (Object options : List.of("max_connections", List.of(Map.of("value", "500")),
                List.of(Map.of("name", "max_connections", "value", 500)))) {
            assertEquals("InvalidParameter", assertThrows(OciException.class,
                    () -> configurations.createConfiguration(Map.of("compartmentId", COMPARTMENT,
                            "shapeName", "MySQL.2", "options", options))).getCode());
        }
    }

    @Test
    void workRequestsSortByEverySdkFieldInEitherOrder() {
        assertEquals(List.of("wr-a", "wr-b", "wr-c"), workRequestOrder("ID", "ASC"));
        assertEquals(List.of("wr-c", "wr-b", "wr-a"), workRequestOrder("ID", "DESC"));
        assertEquals(List.of("wr-b", "wr-c", "wr-a"), workRequestOrder("OPERATION_TYPE", "ASC"));
        assertEquals(List.of("wr-a", "wr-c", "wr-b"), workRequestOrder("OPERATION_TYPE", "DESC"));
        assertEquals(List.of("wr-c", "wr-b", "wr-a"), workRequestOrder("STATUS", "ASC"));
        assertEquals(List.of("wr-a", "wr-b", "wr-c"), workRequestOrder("STATUS", "DESC"));
        assertEquals(List.of("wr-a", "wr-b", "wr-c"), workRequestOrder("TIME_ACCEPTED", "ASC"));
        assertEquals(List.of("wr-c", "wr-b", "wr-a"), workRequestOrder("TIME_ACCEPTED", "DESC"));
        assertEquals(List.of("wr-a", "wr-b", "wr-c"), workRequestOrder("TIME_STARTED", "ASC"));
        assertEquals(List.of("wr-b", "wr-a", "wr-c"), workRequestOrder("TIME_STARTED", "DESC"));
        assertEquals(List.of("wr-a", "wr-b", "wr-c"), workRequestOrder("TIME_FINISHED", "ASC"));
        assertEquals(List.of("wr-a", "wr-c", "wr-b"), workRequestOrder("TIME_FINISHED", "DESC"));
    }

    @Test
    void workRequestsSortTimeFieldsNewestFirstAndOthersAscendingByDefault() {
        assertEquals(List.of("wr-c", "wr-b", "wr-a"), workRequestOrder(null, null));
        assertEquals(List.of("wr-b", "wr-a", "wr-c"), workRequestOrder("TIME_STARTED", null));
        assertEquals(List.of("wr-a", "wr-b", "wr-c"), workRequestOrder("ID", null));
        assertEquals(List.of("wr-a", "wr-b", "wr-c"), workRequestOrder(null, "ASC"));
    }

    /** Three work requests whose fields order differently; the times mix fraction lengths. */
    private static List<String> workRequestOrder(String sortBy, String sortOrder) {
        StoredWorkRequest a = workRequest("wr-a", "UPDATE_DBSYSTEM", "SUCCEEDED",
                "2026-01-01T00:00:00.100Z", "2026-01-01T00:00:00.100Z", "2026-01-01T00:00:01Z");
        StoredWorkRequest b = workRequest("wr-b", "CREATE_DBSYSTEM", "IN_PROGRESS",
                "2026-01-01T00:00:00.100500Z", "2026-01-01T00:00:00.100500Z", null);
        StoredWorkRequest c = workRequest("wr-c", "DELETE_DBSYSTEM", "ACCEPTED", "2026-01-01T00:00:01Z", null, null);
        return List.of(c, a, b).stream()
                .sorted(MysqlController.workRequestOrder(sortBy, sortOrder))
                .map(StoredWorkRequest::getId)
                .toList();
    }

    private static StoredWorkRequest workRequest(String id, String operationType, String status,
                                                 String accepted, String started, String finished) {
        StoredWorkRequest workRequest = new StoredWorkRequest();
        workRequest.setId(id);
        workRequest.setOperationType(operationType);
        workRequest.setStatus(status);
        workRequest.setTimeAccepted(accepted);
        workRequest.setTimeStarted(started);
        workRequest.setTimeFinished(finished);
        return workRequest;
    }

    @Test
    void aConfigurationInUseCannotBeDeleted() {
        StoredMysqlConfiguration custom = configurations.createConfiguration(Map.of("compartmentId", COMPARTMENT,
                "shapeName", "MySQL.2"));
        Map<String, Object> details = createDetails();
        details.put("configurationId", custom.getId());
        StoredMysqlDbSystem system = mock.createDbSystem(details).system();

        assertEquals("Conflict", assertThrows(OciException.class,
                () -> configurations.deleteConfiguration(custom.getId(), null, mock::usesConfiguration)).getCode());

        mock.deleteDbSystem(system.getId(), null);
        configurations.deleteConfiguration(custom.getId(), null, mock::usesConfiguration);
        assertEquals("DELETED", configurations.configurationWire(custom.getId()).get("lifecycleState"));
    }

    @Test
    void resetRemovesEverySidecarAndEverySystem() {
        StoredMysqlDbSystem system = activeRealSystem();

        real.clear();

        verify(containers).purge(any());
        assertThrows(OciException.class, () -> real.getDbSystem(system.getId()));
    }

    @Test
    void aRestartFailsAnInterruptedCreateAndBringsActiveSystemsBack() {
        StoredMysqlDbSystem active = activeRealSystem();
        MysqlService cutOff = new MysqlService(systems, config, null, workRequests, containers, configurations,
                OciContext.fromConfig(config), ONE_SUBNET, clock, task -> { });
        MysqlService.Created interrupted = cutOff.createDbSystem(createDetails());

        real.restartPersistedSystems();
        real.pollReadiness();

        assertEquals("FAILED", real.getDbSystem(interrupted.system().getId()).getLifecycleState());
        assertEquals(MysqlService.INTERRUPTED,
                workRequest(interrupted.workRequestId()).getErrors().getFirst().getMessage());
        assertEquals("ACTIVE", real.getDbSystem(active.getId()).getLifecycleState());
    }
}
