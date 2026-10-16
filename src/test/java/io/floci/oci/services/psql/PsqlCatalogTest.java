package io.floci.oci.services.psql;

import io.floci.oci.core.common.OciException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PsqlCatalogTest {

    @Test
    void majorComesFromAMajorOrAMajorMinorVersion() {
        assertEquals("14", PsqlCatalog.major("14"));
        assertEquals("15", PsqlCatalog.major("15.8"));
    }

    @Test
    void anUnsupportedVersionIsInvalidParameter() {
        OciException e = assertThrows(OciException.class, () -> PsqlCatalog.major("9.6"));
        assertEquals(400, e.getHttpStatus());
        assertEquals("InvalidParameter", e.getCode());
    }

    @Test
    void theShapeTerraformSendsIsReducedToTheComputeShapeAndItsSize() {
        PsqlCatalog.Shape sized = PsqlCatalog.parseShape("PostgreSQL.VM.Standard.E4.Flex.2.32GB");
        assertEquals("VM.Standard.E4.Flex", sized.name());
        assertEquals(2, sized.ocpuCount());
        assertEquals(32, sized.memorySizeInGBs());

        PsqlCatalog.Shape flexible = PsqlCatalog.parseShape("PostgreSQL.VM.Standard.E5.Flex");
        assertEquals("VM.Standard.E5.Flex", flexible.name());
        assertNull(flexible.ocpuCount());

        assertEquals("VM.Standard.E4.Flex", PsqlCatalog.parseShape("VM.Standard.E4.Flex").name());
    }

    @Test
    void anUnknownShapeIsInvalidParameter() {
        OciException e = assertThrows(OciException.class, () -> PsqlCatalog.parseShape("VM.Standard2.1"));
        assertEquals("InvalidParameter", e.getCode());
    }

    @Test
    void defaultConfigurationIdsAreStableAndRecognisableAsDefaults() {
        String id = PsqlCatalog.defaultConfigurationId("15", null, "oc1", "iad");

        assertEquals(id, PsqlCatalog.defaultConfigurationId("15", null, "oc1", "iad"));
        assertTrue(id.startsWith("ocid1.postgresqldefaultconfiguration.oc1.iad."), id);
        Map<String, Object> flexible = PsqlCatalog.findDefaultConfiguration(id, "oc1", "iad").orElseThrow();
        assertEquals("15", flexible.get("dbVersion"));
        assertEquals(true, flexible.get("isFlexible"));
        assertEquals("PostgreSQL", flexible.get("shape"));
        assertEquals(0, flexible.get("instanceOcpuCount"));
    }

    @Test
    void aSizedShapeHasAFixedConfigurationOfItsOwn() {
        PsqlCatalog.Shape sized = PsqlCatalog.parseShape("PostgreSQL.VM.Standard.E5.Flex.8.128GB");

        String id = PsqlCatalog.defaultConfigurationId("16", sized, "oc1", "phx");

        Map<String, Object> fixed = PsqlCatalog.findDefaultConfiguration(id, "oc1", "phx").orElseThrow();
        assertEquals(false, fixed.get("isFlexible"));
        assertEquals("VM.Standard.E5.Flex", fixed.get("shape"));
        assertEquals(8, fixed.get("instanceOcpuCount"));
        assertEquals(128, fixed.get("instanceMemorySizeInGBs"));
        assertEquals(List.of("VM.Standard.E5.Flex"), fixed.get("compatibleShapes"));
        assertEquals("16", PsqlCatalog.defaultConfigurationMajor(id, "oc1", "phx").orElseThrow());
        // The same configuration in another region is another OCID.
        assertTrue(PsqlCatalog.findDefaultConfiguration(id, "oc1", "iad").isEmpty());
    }

    @Test
    void anIdThatIsNotOneOfTheCatalogsResolvesToNothing() {
        for (String id : List.of("ocid1.postgresqldefaultconfiguration.oc1.iad.nothex",
                "ocid1.postgresqldefaultconfiguration.oc1.iad.6e6f7065",
                "ocid1.postgresqlconfiguration.oc1.iad.custom",
                PsqlCatalog.defaultConfigurationId("99", null, "oc1", "iad"))) {
            assertTrue(PsqlCatalog.findDefaultConfiguration(id, "oc1", "iad").isEmpty(), id);
        }
    }

    @Test
    void theCatalogListsAFlexibleAndAFixedConfigurationPerMajor() {
        List<Map<String, Object>> configurations = PsqlCatalog.defaultConfigurations("oc1", "iad");

        assertEquals(PsqlCatalog.MAJORS.size() * (1 + PsqlCatalog.SHAPES.size()), configurations.size());
        for (Map<String, Object> configuration : configurations) {
            assertTrue(PsqlCatalog.findDefaultConfiguration(configuration.get("id").toString(), "oc1", "iad")
                    .isPresent(), configuration.get("displayName").toString());
        }
        assertEquals(PsqlCatalog.SHAPES, PsqlCatalog.shapes().stream().map(shape -> shape.get("shape")).toList());
    }
}
