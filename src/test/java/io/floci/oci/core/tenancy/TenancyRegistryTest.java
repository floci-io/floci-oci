package io.floci.oci.core.tenancy;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class TenancyRegistryTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..registrytenancy";

    private EmulatorConfig config;
    private StorageBackend<String, StoredTenancy> raw;
    private TenancyRegistry registry;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        raw = new InMemoryStorage<>();
        registry = new TenancyRegistry(new TenancyAwareStorageBackend<>(raw, () -> TENANCY),
                config, OciContext.fromConfig(config));
    }

    @Test
    void anUnknownTenancyIsRegisteredWithTheDefaultHomeRegion() {
        StoredTenancy tenancy = registry.current();

        assertEquals(TENANCY, tenancy.getId());
        assertEquals("us-ashburn-1", tenancy.getHomeRegion());
        assertEquals(TenancyRegistry.DEFAULT_NAME, tenancy.getName());
        assertTrue(raw.get(TENANCY + "/" + TENANCY).isPresent(), "stored in its own partition");
    }

    @Test
    void theHomeRegionStaysPinnedWhenTheDefaultRegionChanges() {
        registry.current();
        lenient().when(config.defaultRegion()).thenReturn("us-phoenix-1");

        assertEquals("us-ashburn-1", registry.homeRegion(TENANCY));
    }

    @Test
    void findDoesNotRegister() {
        assertTrue(registry.find("ocid1.tenancy.oc1..unseen").isEmpty());
        assertEquals(0, raw.keys().size());
    }

    @Test
    void aChildIsRegisteredInItsOwnPartitionWithItsHomeRegion() {
        StoredTenancy child = registry.registerChild(TENANCY, "child", "us-phoenix-1");

        assertTrue(Ocids.isOfType(child.getId(), "tenancy"));
        assertEquals("us-phoenix-1", registry.homeRegion(child.getId()));
        assertEquals(TENANCY, registry.find(child.getId()).orElseThrow().getParentTenancyId());
        assertTrue(raw.get(child.getId() + "/" + child.getId()).isPresent());
    }

    @Test
    void aChildTenancyOcidCarriesTheRealmOfItsHomeRegion() {
        StoredTenancy child = registry.registerChild(TENANCY, "uk-gov", "uk-gov-london-1");

        assertEquals("oc4", Ocids.parse(child.getId()).orElseThrow().realm());
    }
}
