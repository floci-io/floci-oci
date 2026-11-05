package io.floci.oci.services.psql;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.psql.model.StoredBackup;
import io.floci.oci.services.psql.model.StoredDbSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PsqlBackupsServiceTest {

    private static final String COMPARTMENT = "ocid1.tenancy.oc1..psqlbackups";

    /** A clock the tests move forward, so backups get distinct creation times. */
    private static final class SteppingClock extends Clock {
        private Instant now = Instant.parse("2026-10-29T10:00:00Z");

        void advance(long minutes) {
            now = now.plus(minutes, ChronoUnit.MINUTES);
        }

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
            return now;
        }
    }

    private EmulatorConfig config;
    private WorkRequestService workRequests;
    private PsqlContainerManager containers;
    private SteppingClock clock;
    private PsqlBackupsService mock;
    private PsqlBackupsService real;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultTenancyId()).thenReturn(COMPARTMENT);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.hostname()).thenReturn(Optional.empty());
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        containers = mock(PsqlContainerManager.class);
        lenient().when(containers.dump(any(), anyString())).thenReturn(3L * 1024 * 1024 * 1024 + 1);
        clock = new SteppingClock();
        InMemoryStorage<String, StoredBackup> store = new InMemoryStorage<>();
        mock = new PsqlBackupsService(store, workRequests, null, OciContext.fromConfig(config), config, clock,
                Runnable::run);
        real = new PsqlBackupsService(store, workRequests, containers, OciContext.fromConfig(config), config, clock,
                Runnable::run);
    }

    private static StoredDbSystem system(String id) {
        StoredDbSystem system = new StoredDbSystem();
        system.setId(id);
        system.setSystemType("OCI_OPTIMIZED_STORAGE");
        system.setDbVersion("15.8");
        system.setManagementPolicy(new HashMap<>());
        return system;
    }

    private static Map<String, Object> details(String name) {
        return Map.of("displayName", name, "compartmentId", COMPARTMENT, "dbSystemId", "ignored");
    }

    private StoredBackup only(PsqlBackupsService service) {
        return service.listBackups(null, null, null, null, null, null, null, null, null, null).getFirst();
    }

    @Test
    void aMockBackupIsActiveAtOnce() {
        String workRequestId = mock.createBackup(system("ocid1.postgresqldbsystem.oc1.iad.a"), details("nightly"));

        StoredWorkRequest wr = workRequests.get(workRequestId);
        assertEquals("CREATE_POSTGRESQL_DB_SYSTEM_BACKUP", wr.getOperationType());
        assertEquals("SUCCEEDED", wr.getStatus());
        StoredBackup backup = only(mock);
        assertEquals("ACTIVE", backup.getLifecycleState());
        assertEquals("MANUAL", backup.getSourceType());
        assertEquals("15", backup.getDbSystemDetails().get("dbVersion"));
        assertTrue(wr.getResources().getFirst().getEntityType().toLowerCase().contains("backup"),
                "Terraform finds the backup by an entity type containing backup");
    }

    @Test
    void aRealBackupDumpsTheSystemAndReportsItsSize() {
        StoredDbSystem system = system("ocid1.postgresqldbsystem.oc1.iad.b");

        String workRequestId = real.createBackup(system, details("nightly"));

        StoredBackup backup = only(real);
        verify(containers).dump(system, backup.getDumpId());
        assertEquals("ACTIVE", backup.getLifecycleState());
        assertEquals(4, backup.getBackupSize(), "rounded up to whole gigabytes");
        assertEquals("SUCCEEDED", workRequests.get(workRequestId).getStatus());
        assertNotNull(workRequests.get(workRequestId).getTimeFinished());
    }

    @Test
    void aFailedDumpFailsTheBackupAndItsWorkRequest() {
        when(containers.dump(any(), anyString())).thenThrow(new IllegalStateException("disk full"));

        String workRequestId = real.createBackup(system("ocid1.postgresqldbsystem.oc1.iad.c"), details("nightly"));

        StoredBackup backup = only(real);
        assertEquals("FAILED", backup.getLifecycleState());
        assertTrue(backup.getLifecycleDetails().contains("disk full"));
        assertEquals("FAILED", workRequests.get(workRequestId).getStatus());
    }

    @Test
    void onlyTenBackupsPerTenancyAndRegion() {
        for (int i = 0; i < PsqlBackupsService.LIMIT; i++) {
            mock.createBackup(system("ocid1.postgresqldbsystem.oc1.iad.d"), details("b" + i));
        }

        OciException e = assertThrows(OciException.class,
                () -> mock.createBackup(system("ocid1.postgresqldbsystem.oc1.iad.d"), details("over")));
        assertEquals("LimitExceeded", e.getCode());

        mock.deleteBackup(only(mock).getId(), null);
        mock.createBackup(system("ocid1.postgresqldbsystem.oc1.iad.d"), details("fits"));
    }

    @Test
    void deleteLeavesADeletedRecordAndReportsTheBackup() {
        mock.createBackup(system("ocid1.postgresqldbsystem.oc1.iad.e"), details("nightly"));
        StoredBackup backup = only(mock);

        StoredWorkRequest wr = workRequests.get(mock.deleteBackup(backup.getId(), null));

        assertEquals("DELETE_POSTGRESQL_DB_SYSTEM_BACKUP", wr.getOperationType());
        assertEquals(backup.getId(), wr.getResources().getFirst().getIdentifier());
        assertEquals("DELETED", mock.getBackup(backup.getId()).getLifecycleState());
        assertEquals("Conflict", assertThrows(OciException.class,
                () -> mock.deleteBackup(backup.getId(), null)).getCode());
    }

    @Test
    void aCopySharesTheDumpAndIsRecordedOnTheSource() {
        mock.createBackup(system("ocid1.postgresqldbsystem.oc1.iad.f"), details("nightly"));
        StoredBackup source = only(mock);

        mock.copyBackup(source.getId(), Map.of("compartmentId", COMPARTMENT, "regions", List.of("us-phoenix-1")),
                null);

        List<Map<String, Object>> copyStatus = mock.getBackup(source.getId()).getCopyStatus();
        assertEquals(1, copyStatus.size());
        assertEquals("COPIED", copyStatus.getFirst().get("state"));
        StoredBackup copy = mock.getBackup((String) copyStatus.getFirst().get("backupId"));
        assertEquals("COPIED", copy.getSourceType());
        assertEquals(source.getDumpId(), copy.getDumpId());
        assertEquals(Map.of("sourceRegion", "us-ashburn-1", "sourceBackupId", source.getId()),
                copy.getSourceBackupDetails());
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> mock.copyBackup(source.getId(),
                Map.of("compartmentId", COMPARTMENT, "regions", List.of("mars-1")), null)).getCode());
    }

    @Test
    void aCopyThatWouldPassTheLimitIsRefusedWholeInItsRegion() {
        String dbSystemId = "ocid1.postgresqldbsystem.oc1.iad.limit";
        for (int i = 0; i < PsqlBackupsService.LIMIT; i++) {
            mock.createBackup(system(dbSystemId), details("b" + i));
        }
        StoredBackup source = only(mock);

        assertEquals("LimitExceeded", assertThrows(OciException.class, () -> mock.copyBackup(source.getId(),
                Map.of("compartmentId", COMPARTMENT, "regions", List.of("us-phoenix-1", "us-ashburn-1")), null)).getCode());
        assertEquals(PsqlBackupsService.LIMIT, liveBackups(), "the copy to the region with room was not made either");

        mock.deleteBackup(source.getId(), null);
        StoredBackup kept = mock.listBackups(null, null, null, "ACTIVE", null, null, null, null, null, null).getFirst();
        mock.copyBackup(kept.getId(), Map.of("compartmentId", COMPARTMENT, "regions", List.of("us-ashburn-1")), null);
        assertEquals(PsqlBackupsService.LIMIT, liveBackups(), "a freed slot takes one copy");
    }

    @Test
    void repeatingARegionCountsEveryCopyAgainstItsLimit() {
        mock.createBackup(system("ocid1.postgresqldbsystem.oc1.iad.repeat"), details("source"));
        StoredBackup source = only(mock);
        List<String> tooMany = Collections.nCopies(PsqlBackupsService.LIMIT + 1, "us-phoenix-1");

        assertEquals("LimitExceeded", assertThrows(OciException.class, () -> mock.copyBackup(source.getId(),
                Map.of("compartmentId", COMPARTMENT, "regions", tooMany), null)).getCode());
        assertEquals(1, liveBackups());
    }

    private long liveBackups() {
        return mock.listBackups(null, null, null, null, null, null, null, null, null, null).stream()
                .filter(b -> !"DELETED".equals(b.getLifecycleState()))
                .count();
    }

    @Test
    void aCopyDoesNotMakeItsOlderDumpTheNewestToRecoverTo() {
        String dbSystemId = "ocid1.postgresqldbsystem.oc1.iad.copyorder";
        mock.createBackup(system(dbSystemId), details("older"));
        StoredBackup older = only(mock);
        clock.advance(60);
        mock.createBackup(system(dbSystemId), details("newer"));
        clock.advance(60);

        mock.copyBackup(older.getId(), Map.of("compartmentId", COMPARTMENT, "regions", List.of("us-ashburn-1")), null);

        StoredBackup recovered = mock.requireAtOrBefore(dbSystemId, "2026-10-29T12:30:00Z");
        assertEquals("newer", recovered.getDisplayName());
    }

    @Test
    void aBackupTheRestartInterruptedFailsAndCanBeDeleted() {
        PsqlBackupsService interrupted = new PsqlBackupsService(new InMemoryStorage<>(), workRequests, containers,
                OciContext.fromConfig(config), config, clock, command -> { });
        String workRequestId = interrupted.createBackup(system("ocid1.postgresqldbsystem.oc1.iad.cut"), details("cut"));
        StoredBackup backup = only(interrupted);
        assertEquals("CREATING", backup.getLifecycleState());
        assertEquals("Conflict", assertThrows(OciException.class,
                () -> interrupted.deleteBackup(backup.getId(), null)).getCode());

        interrupted.settleInterruptedBackups();

        StoredBackup settled = interrupted.getBackup(backup.getId());
        assertEquals("FAILED", settled.getLifecycleState());
        assertTrue(settled.getLifecycleDetails().contains("restarted"));
        assertEquals("FAILED", workRequests.get(workRequestId).getStatus());
        interrupted.deleteBackup(backup.getId(), null);
        assertEquals("DELETED", interrupted.getBackup(backup.getId()).getLifecycleState());
    }

    @Test
    void aPointInTimeResolvesToTheNewestBackupAtOrBeforeIt() {
        String dbSystemId = "ocid1.postgresqldbsystem.oc1.iad.g";
        mock.createBackup(system(dbSystemId), details("first"));
        clock.advance(60);
        mock.createBackup(system(dbSystemId), details("second"));
        clock.advance(60);

        assertEquals("first", mock.requireAtOrBefore(dbSystemId, "2026-10-29T10:30:00Z").getDisplayName());
        assertEquals("second", mock.requireAtOrBefore(dbSystemId, "2026-10-29T11:00:00Z").getDisplayName());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> mock.requireAtOrBefore(dbSystemId, "2026-10-29T09:00:00Z")).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> mock.requireAtOrBefore(dbSystemId, "yesterday")).getCode());
    }

    @Test
    void pitrDetailsFollowThePitrPolicy() {
        StoredDbSystem system = system("ocid1.postgresqldbsystem.oc1.iad.h");
        mock.createBackup(system, details("first"));

        assertEquals("INACTIVE", mock.pitrDetails(system).get("pitrState"));
        system.getManagementPolicy().put("pitrPolicy", Map.of("kind", "STANDARD", "restoreDays", 7));
        Map<String, Object> details = mock.pitrDetails(system);
        assertEquals("ACTIVE", details.get("pitrState"));
        assertEquals(1, ((List<?>) details.get("recoveryTimeWindows")).size());
    }

    @Test
    void listFiltersAndValidates() {
        mock.createBackup(system("ocid1.postgresqldbsystem.oc1.iad.i"), details("b"));
        clock.advance(1);
        mock.createBackup(system("ocid1.postgresqldbsystem.oc1.iad.j"), details("a"));

        assertEquals(List.of("a", "b"), mock.listBackups(null, null, null, null, null, null, null, null,
                "displayName", null).stream().map(StoredBackup::getDisplayName).toList());
        assertEquals(1, mock.listBackups(null, null, null, null, null, null, "ocid1.postgresqldbsystem.oc1.iad.i",
                "MANUAL", null, null).size());
        assertNotEquals(0, mock.listBackups(null, "2026-10-29T00:00:00Z", null, "ACTIVE", null, null, null, null,
                null, null).size());
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> mock.listBackups(null, null, null,
                "UPDATING", null, null, null, null, null, null)).getCode());
    }
}
