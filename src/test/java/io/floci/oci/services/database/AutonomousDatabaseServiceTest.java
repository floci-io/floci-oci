package io.floci.oci.services.database;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.NetworkReferences;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.database.model.StoredAutonomousDatabase;
import io.floci.oci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutonomousDatabaseServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..adbtesttenancy";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..adbtestcompartment";
    private static final String PASSWORD = "Welcome12345#";
    private static final AutonomousDatabaseService.ListFilter NO_FILTER = new AutonomousDatabaseService.ListFilter(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    private EmulatorConfig config;
    private InMemoryStorage<String, StoredAutonomousDatabase> databases;
    private WorkRequestService workRequests;
    private MutableClock clock;
    private AutonomousDatabaseService service;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.hostname()).thenReturn(Optional.empty());
        lenient().when(config.services().database().mock()).thenReturn(true);
        databases = new InMemoryStorage<>();
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        clock = new MutableClock();
        service = new AutonomousDatabaseService(databases, config, null, workRequests, OciContext.fromConfig(config),
                NetworkReferences.NONE, clock);
    }

    private static Map<String, Object> details(String dbName) {
        Map<String, Object> details = new HashMap<>();
        details.put("compartmentId", COMPARTMENT);
        details.put("dbName", dbName);
        details.put("adminPassword", PASSWORD);
        return details;
    }

    @Test
    void createStoresNoPasswordAndEchoesTheVersionAndCharacterSets() {
        Map<String, Object> details = details("orders");
        details.put("dbVersion", "23ai");
        details.put("ncharacterSet", "UTF8");
        details.put("isPreviewVersionWithServiceTermsAccepted", true);

        AutonomousDatabaseService.Result result = service.create(details);

        Map<String, Object> wire = result.database().toWire();
        assertEquals("AVAILABLE", wire.get("lifecycleState"));
        assertEquals("23ai", wire.get("dbVersion"));
        assertEquals("AL32UTF8", wire.get("characterSet"));
        assertEquals("UTF8", wire.get("ncharacterSet"));
        assertFalse(wire.containsKey("adminPassword"));
        assertFalse(wire.containsKey("isPreviewVersionWithServiceTermsAccepted"),
                "a create-only field that is not on AutonomousDatabase is not echoed");
        assertFalse(result.database().getAttributes().containsValue(PASSWORD));
        StoredWorkRequest workRequest = workRequests.get(AutonomousDatabaseService.SERVICE, result.workRequestId());
        assertEquals("SUCCEEDED", workRequest.getStatus());
        assertEquals("autonomousDatabase", workRequest.getResources().getFirst().getEntityType());
        assertEquals("CREATED", workRequest.getResources().getFirst().getActionType());
    }

    @Test
    void aServiceWithoutContainersStaysInMockModeWhateverTheFlag() {
        when(config.services().database().mock()).thenReturn(false);

        assertEquals("AVAILABLE", service.create(details("orders")).database().getLifecycleState());
    }

    @Test
    void aSecretIdStandsInForTheAdminPassword() {
        Map<String, Object> details = details("secretdb");
        details.remove("adminPassword");
        details.put("secretId", "ocid1.vaultsecret.oc1.iad.admin");

        assertEquals("AVAILABLE", service.create(details).database().getLifecycleState());

        details.remove("secretId");
        assertEquals("MissingParameter", assertThrows(OciException.class, () -> service.create(details)).getCode());
    }

    @Test
    void adminPasswordsFollowOraclesRule() {
        for (String weak : List.of("Short1a", "alllowercase123", "ALLUPPERCASE123", "NoDigitsHereAtAll",
                "Has\"Quote12345", "MyAdminPass123", "TooLong1234567890123456789012345")) {
            assertThrows(OciException.class, () -> AutonomousDatabaseService.requireAdminPassword(weak), weak);
        }
        AutonomousDatabaseService.requireAdminPassword("Welcome12345#");
    }

    @Test
    void walletPasswordsNeedALetterAndADigitOrSymbol() {
        assertThrows(OciException.class, () -> AutonomousDatabaseService.requireWalletPassword("abcdefgh"));
        assertThrows(OciException.class, () -> AutonomousDatabaseService.requireWalletPassword("a1"));
        AutonomousDatabaseService.requireWalletPassword("abcdefg1");
        AutonomousDatabaseService.requireWalletPassword("abcdefg#");
    }

    @Test
    void storageInGigabytesRoundsTheTerabyteFigureUp() {
        Map<String, Object> details = details("sized");
        details.put("dataStorageSizeInGBs", 1500);

        Map<String, Object> wire = service.create(details).database().toWire();

        assertEquals(1500, wire.get("dataStorageSizeInGBs"));
        assertEquals(2, wire.get("dataStorageSizeInTBs"));
    }

    @Test
    void anOcpuDatabaseIsSizedByItsCpuCount() {
        Map<String, Object> details = details("ocpudb");
        details.put("computeModel", "OCPU");
        details.put("cpuCoreCount", 3);

        Map<String, Object> wire = service.create(details).database().toWire();

        assertEquals(3, wire.get("cpuCoreCount"));
        assertEquals(3, wire.get("computeCount"));
    }

    @Test
    void anUpdateWithoutComputeFieldsKeepsTheOcpuCount() {
        Map<String, Object> details = details("ocpukeep");
        details.put("computeModel", "OCPU");
        details.put("cpuCoreCount", 3);
        String id = service.create(details).database().getId();

        service.update(id, Map.of("displayName", "renamed"), null);

        Map<String, Object> wire = service.get(id).toWire();
        assertEquals(3, wire.get("computeCount"));
        assertEquals(3, wire.get("cpuCoreCount"));
    }

    @Test
    void listFiltersByResourcePoolLeaderId() {
        Map<String, Object> member = details("poolmember");
        member.put("resourcePoolLeaderId", "ocid1.autonomousdatabase.oc1.iad.leader");
        String memberId = service.create(member).database().getId();
        service.create(details("loner"));

        AutonomousDatabaseService.ListFilter byLeader = new AutonomousDatabaseService.ListFilter(null, null, null,
                null, null, null, null, null, null, "ocid1.autonomousdatabase.oc1.iad.leader", null, null, null, null);
        assertEquals(List.of(memberId), service.list(COMPARTMENT, byLeader).stream()
                .map(StoredAutonomousDatabase::getId).toList());
    }

    @Test
    void aRejectedUpdateChangesNothing() {
        StoredAutonomousDatabase database = service.create(details("atomic")).database();
        String etag = database.getEtag();

        assertThrows(OciException.class, () -> service.update(database.getId(),
                Map.of("displayName", "renamed", "licenseModel", "FREE_FOR_EVER"), null));

        StoredAutonomousDatabase after = service.get(database.getId());
        assertEquals("atomic", after.getDisplayName());
        assertEquals("LICENSE_INCLUDED", after.getAttributes().get("licenseModel"));
        assertEquals(etag, after.getEtag());
    }

    @Test
    void renamingTheDatabaseRenamesItsConnectionStrings() {
        StoredAutonomousDatabase database = service.create(details("before")).database();

        service.update(database.getId(), Map.of("dbName", "after"), null);

        @SuppressWarnings("unchecked")
        Map<String, Object> strings = (Map<String, Object>) service.get(database.getId()).toWire()
                .get("connectionStrings");
        assertEquals("localhost:1522/after_high.adb.oraclecloud.com", strings.get("high"));
    }

    @Test
    void aTerminatedDatabaseKeepsItsTombstoneButLeavesTheCompartment() {
        StoredAutonomousDatabase database = service.create(details("gone")).database();
        assertEquals(List.of("Autonomous Database " + database.getId()), service.resourcesIn(TENANCY, COMPARTMENT));

        service.delete(database.getId(), null);

        assertEquals("TERMINATED", service.get(database.getId()).getLifecycleState());
        assertTrue(service.resourcesIn(TENANCY, COMPARTMENT).isEmpty());
        assertEquals("NotAuthorizedOrNotFound",
                assertThrows(OciException.class, () -> service.stop(database.getId(), null)).getCode());
        assertEquals("NotAuthorizedOrNotFound",
                assertThrows(OciException.class, () -> service.generateWallet(database.getId(),
                        Map.of("password", "WalletPass1"))).getCode());
    }

    @Test
    void aDatabaseReportsItsSubnetAndNsgsUntilItIsTerminated() {
        String subnet = "ocid1.subnet.oc1.iad.adbsubnet";
        String nsg = "ocid1.networksecuritygroup.oc1.iad.adbnsg";
        Map<String, Object> details = details("private");
        details.put("subnetId", subnet);
        details.put("nsgIds", List.of(nsg));
        StoredAutonomousDatabase database = service.create(details).database();
        service.create(details("public"));

        List<String> using = List.of("Autonomous Database " + database.getId());
        assertEquals(using, service.resourcesUsing(subnet));
        assertEquals(using, service.resourcesUsing(nsg));

        service.delete(database.getId(), null);
        assertEquals(List.of(), service.resourcesUsing(subnet));
    }

    @Test
    void listSortsNewestFirstByDefaultAndByNameWhenAsked() {
        String older = service.create(details("bravo")).database().getId();
        clock.advance(Duration.ofSeconds(5));
        String newer = service.create(details("alpha")).database().getId();

        assertEquals(List.of(newer, older), service.list(COMPARTMENT, NO_FILTER).stream()
                .map(StoredAutonomousDatabase::getId).toList());
        AutonomousDatabaseService.ListFilter byName = new AutonomousDatabaseService.ListFilter(null, null, null,
                null, null, null, null, null, null, null, null, null, "DISPLAYNAME", null);
        assertEquals(List.of(newer, older), service.list(COMPARTMENT, byName).stream()
                .map(StoredAutonomousDatabase::getId).toList());
        AutonomousDatabaseService.ListFilter byNameDescending = new AutonomousDatabaseService.ListFilter(null, null,
                null, null, null, null, null, null, null, null, null, null, "DISPLAYNAME", "DESC");
        assertEquals(List.of(older, newer), service.list(COMPARTMENT, byNameDescending).stream()
                .map(StoredAutonomousDatabase::getId).toList());
    }

    @Test
    void dbNamesAreUniqueInTheTenancyUntilTheDatabaseIsTerminated() {
        StoredAutonomousDatabase first = service.create(details("Orders")).database();

        assertEquals("InvalidParameter",
                assertThrows(OciException.class, () -> service.create(details("ORDERS"))).getCode());
        StoredAutonomousDatabase other = service.create(details("billing")).database();
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> service.update(other.getId(), Map.of("dbName", "orders"), null)).getCode());

        service.delete(first.getId(), null);
        assertEquals("AVAILABLE", service.create(details("orders")).database().getLifecycleState());
    }

    @Test
    void generatedNamesNeverCollide() {
        Map<String, Object> unnamed = details("ignored");
        unnamed.remove("dbName");

        String firstName = service.create(unnamed).database().getDbName();
        String secondName = service.create(unnamed).database().getDbName();

        assertNotEquals(firstName, secondName);
        assertEquals(firstName + "2", secondName);
    }

    @Test
    void clearForgetsEveryDatabase() {
        service.create(details("cleared"));

        service.clear();

        assertTrue(service.list(COMPARTMENT, NO_FILTER).isEmpty());
    }
}
