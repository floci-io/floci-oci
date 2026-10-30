package io.floci.oci.services.psql;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.services.psql.model.StoredConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class PsqlConfigurationsServiceTest {

    private static final String COMPARTMENT = "ocid1.tenancy.oc1..psqlconfig";

    private PsqlConfigurationsService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultTenancyId()).thenReturn(COMPARTMENT);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.hostname()).thenReturn(Optional.empty());
        service = new PsqlConfigurationsService(new InMemoryStorage<>(), OciContext.fromConfig(config),
                Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC));
    }

    private static Map<String, Object> overrides(String... keyValues) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            items.add(Map.of("configKey", keyValues[i], "overridenConfigValue", keyValues[i + 1]));
        }
        return Map.of("items", items);
    }

    private StoredConfiguration flexible(String name, String... keyValues) {
        return service.createConfiguration(Map.of("displayName", name, "compartmentId", COMPARTMENT,
                "dbVersion", "15", "isFlexible", true, "dbConfigurationOverrides", overrides(keyValues)));
    }

    @Test
    void aFlexibleConfigurationReportsPostgreSqlAndZeroSizes() {
        StoredConfiguration configuration = flexible("tuned", "work_mem", "16MB");

        assertEquals("ACTIVE", configuration.getLifecycleState());
        assertEquals("PostgreSQL", configuration.getShape());
        assertEquals(0, configuration.getInstanceOcpuCount());
        assertEquals(0, configuration.getInstanceMemorySizeInGBs());
        assertTrue(configuration.getIsFlexible());
        assertTrue(configuration.getId().startsWith("ocid1.postgresqlconfiguration.oc1.iad."));
        assertFalse(configuration.getId().contains("defaultconfiguration"), "Terraform tells them apart by that");
        Map<String, Object> wire = configuration.toWire();
        assertEquals("CUSTOM", wire.get("configType"));
        assertTrue(((String) wire.get("defaultConfigId")).contains("defaultconfiguration"));
        List<?> items = (List<?>) ((Map<?, ?>) wire.get("configurationDetails")).get("items");
        assertEquals("16MB", ((Map<?, ?>) items.getFirst()).get("overridenConfigValue"));
    }

    @Test
    void aFixedConfigurationTakesItsSizeFromTheShape() {
        StoredConfiguration configuration = service.createConfiguration(Map.of("displayName", "fixed",
                "compartmentId", COMPARTMENT, "dbVersion", "15", "shape", "VM.Standard.E4.Flex.4.64GB",
                "dbConfigurationOverrides", overrides()));

        assertEquals("VM.Standard.E4.Flex", configuration.getShape());
        assertEquals(4, configuration.getInstanceOcpuCount());
        assertEquals(64, configuration.getInstanceMemorySizeInGBs());
        assertEquals(List.of("VM.Standard.E4.Flex"), configuration.getCompatibleShapes());
        assertEquals("MissingParameter", assertThrows(OciException.class, () -> service.createConfiguration(
                Map.of("displayName", "x", "compartmentId", COMPARTMENT, "dbVersion", "15",
                        "shape", "VM.Standard.E4.Flex", "dbConfigurationOverrides", overrides()))).getCode());
    }

    @Test
    void aFlexibleConfigurationForANamedShapeKeepsTheShape() {
        StoredConfiguration configuration = service.createConfiguration(Map.of("displayName", "flex-e4",
                "compartmentId", COMPARTMENT, "dbVersion", "15", "shape", "VM.Standard.E4.Flex", "isFlexible", true,
                "dbConfigurationOverrides", overrides()));

        assertEquals("VM.Standard.E4.Flex", configuration.getShape(), "Terraform reads the shape back");
        assertEquals(0, configuration.getInstanceOcpuCount());
        assertTrue(configuration.getIsFlexible());
        assertEquals(List.of("VM.Standard.E4.Flex"), configuration.getCompatibleShapes());
    }

    @Test
    void overridesMustBeCatalogSettingsGivenOnce() {
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> flexible("x", "fsync", "off")).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> flexible("x", "work_mem", "8MB", "work_mem", "16MB")).getCode());
        assertEquals("MissingParameter", assertThrows(OciException.class, () -> service.createConfiguration(
                Map.of("displayName", "x", "compartmentId", COMPARTMENT, "dbVersion", "15"))).getCode());
    }

    @Test
    void onlyTwentyConfigurationsPerTenancyAndRegion() {
        for (int i = 0; i < PsqlConfigurationsService.LIMIT; i++) {
            flexible("c" + i);
        }

        OciException e = assertThrows(OciException.class, () -> flexible("one-too-many"));
        assertEquals(400, e.getHttpStatus());
        assertEquals("LimitExceeded", e.getCode());

        String first = service.listConfigurations(null, null, null, "c0", null, null, null, null, null, null, null)
                .getFirst().getId();
        service.deleteConfiguration(first, null, id -> false);
        assertEquals("ACTIVE", flexible("fits-again").getLifecycleState());
    }

    @Test
    void updateChangesOnlyTheSdkFields() {
        StoredConfiguration configuration = flexible("before", "work_mem", "16MB");
        String etag = configuration.getEtag();

        assertEquals(412, assertThrows(OciException.class, () -> service.updateConfiguration(configuration.getId(),
                Map.of("displayName", "after"), "stale")).getHttpStatus());
        StoredConfiguration updated = service.updateConfiguration(configuration.getId(),
                Map.of("displayName", "after", "description", "d", "dbVersion", "16"), etag);

        assertEquals("after", updated.getDisplayName());
        assertEquals("d", updated.getDescription());
        assertEquals("15", updated.getDbVersion(), "dbVersion cannot change");
    }

    @Test
    void aConfigurationInUseCannotBeDeleted() {
        StoredConfiguration configuration = flexible("used");

        assertEquals("Conflict", assertThrows(OciException.class,
                () -> service.deleteConfiguration(configuration.getId(), null, id -> true)).getCode());

        service.deleteConfiguration(configuration.getId(), null, id -> false);
        assertEquals("DELETED", service.getConfiguration(configuration.getId()).getLifecycleState());
        assertEquals("Conflict", assertThrows(OciException.class,
                () -> service.deleteConfiguration(configuration.getId(), null, id -> false)).getCode());
    }

    @Test
    void listFiltersAndValidates() {
        flexible("b-config");
        flexible("a-config");

        assertEquals(List.of("a-config", "b-config"), service.listConfigurations(null, null, null, null, null, null,
                null, null, null, "displayName", null).stream().map(StoredConfiguration::getDisplayName).toList());
        assertEquals(2, service.listConfigurations(null, null, "CUSTOM", null, "15.8", null, null, 0, 0, null, null)
                .size());
        assertTrue(service.listConfigurations(null, null, "DEFAULT", null, null, null, null, null, null, null, null)
                .isEmpty());
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> service.listConfigurations(
                null, "CREATING", null, null, null, null, null, null, null, null, null)).getCode());
    }
}
