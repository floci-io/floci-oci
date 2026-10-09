package io.floci.oci.core.common;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RegionsTest {

    @Test
    void codesComeFromTheSdkTableNotTheRegionName() {
        assertEquals("iad", Regions.code("us-ashburn-1"));
        assertEquals("gru", Regions.code("sa-saopaulo-1"));
        assertEquals("nrt", Regions.code("ap-tokyo-1"));
        assertEquals("bom", Regions.code("ap-mumbai-1"));
        assertEquals("gru", Ocids.regionShort("sa-saopaulo-1"));
    }

    @Test
    void keyIsTheUppercaseCode() {
        assertEquals("IAD", Regions.key("us-ashburn-1"));
        assertEquals("NRT", Regions.key("ap-tokyo-1"));
    }

    @Test
    void regionsCarryTheirRealm() {
        assertEquals("oc1", Regions.byName("us-ashburn-1").orElseThrow().realm());
        assertEquals("oc4", Regions.byName("uk-gov-london-1").orElseThrow().realm());
        assertEquals("oc19", Regions.byName("eu-frankfurt-2").orElseThrow().realm());
    }

    @Test
    void tableHasUniqueNamesAndThreeLetterCodes() {
        assertEquals(85, Regions.all().size());
        Set<String> codes = Regions.all().stream().map(Regions.Region::code)
                .collect(Collectors.toSet());
        assertEquals(Regions.all().size(), codes.size());
        assertTrue(codes.stream().allMatch(c -> c.matches("[a-z]{3}")), codes.toString());
    }

    @Test
    void unknownRegionFallsBackToFirstThreeLetters() {
        assertTrue(Regions.byName("xx-nowhere-1").isEmpty());
        assertEquals("xxn", Regions.code("xx-nowhere-1"));
    }

    @Test
    void regionIsReadFromTheHostHeader() {
        assertEquals("us-phoenix-1",
                Regions.inHost("queue.us-phoenix-1.oci.oraclecloud.com").orElseThrow());
        assertEquals("sa-saopaulo-1",
                Regions.inHost("objectstorage.sa-saopaulo-1.oraclecloud.com:443").orElseThrow());
        assertTrue(Regions.inHost("localhost:4599").isEmpty());
        assertTrue(Regions.inHost(null).isEmpty());
    }
}
