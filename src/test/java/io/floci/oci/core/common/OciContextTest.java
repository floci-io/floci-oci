package io.floci.oci.core.common;

import io.floci.oci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class OciContextTest {

    private static OciContext context(String region, String realm) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRegion()).thenReturn(region);
        lenient().when(config.defaultRealm()).thenReturn(realm);
        lenient().when(config.defaultTenancyId()).thenReturn("ocid1.tenancy.oc1..t");
        return OciContext.fromConfig(config);
    }

    @Test
    void realmFollowsTheRegion() {
        OciContext ctx = context("uk-gov-london-1", "oc1");
        assertEquals("oc4", ctx.realm());
        assertEquals("ltn", ctx.regionCode());
    }

    @Test
    void defaultRealmAppliesOnlyToUnknownRegions() {
        assertEquals("oc9", context("xx-nowhere-1", "oc9").realm());
        assertEquals("oc1", context("us-ashburn-1", "oc9").realm());
    }

    @Test
    void outsideARequestTheDefaultsApply() {
        OciContext ctx = context("sa-saopaulo-1", "oc1");
        assertEquals("sa-saopaulo-1", ctx.region());
        assertEquals("sa-saopaulo-1", ctx.homeRegion());
        assertEquals("ocid1.tenancy.oc1..t", ctx.tenancyId());
    }

    @Test
    void regionOfReadsTheRegionalOcidSegment() {
        OciContext ctx = context("us-ashburn-1", "oc1");
        assertEquals("us-phoenix-1", ctx.regionOf("ocid1.cluster.oc1.phx.aaaa"));
        assertEquals("us-ashburn-1", ctx.regionOf("ocid1.coreservicesworkrequest.oc1..aaaa"));
        assertEquals("us-ashburn-1", ctx.regionOf("not-an-ocid"));
    }

    @Test
    void regionOfMapsACustomDefaultRegionCodeToThatRegion() {
        assertEquals("xx-nowhere-1", context("xx-nowhere-1", "oc9").regionOf("ocid1.cluster.oc9.xxn.aaaa"));
    }

    @Test
    void regionOfPrefersACustomDefaultRegionWhoseCodeMatchesAKnownRegion() {
        assertEquals("iad-local", context("iad-local", "oc1").regionOf("ocid1.cluster.oc1.iad.aaaa"));
    }
}
