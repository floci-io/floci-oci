package io.floci.oci.services.mysql;

import io.floci.oci.core.common.OciException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The static catalog of MySQL HeatWave: shapes, server versions and the default configurations.
 *
 * <p>Shapes and their CPU and memory sizes come from Oracle's Supported Shapes page
 * (https://docs.oracle.com/en-us/iaas/mysql-database/doc/supported-shapes.html), which also names
 * the two default configurations of each shape, {@code <shape>.Standalone} and {@code <shape>.HA}.
 * The versions are a subset of Oracle's MySQL Server Versions page
 * (https://docs.oracle.com/en-us/iaas/mysql-database/doc/mysql-server-versions.html), picked so each
 * one exists as a {@code mysql} image tag; the family labels are floci-oci's.
 */
public final class MysqlCatalog {

    /** A shape: its name, its CPU cores (ECPUs or OCPUs), its memory and whether HeatWave runs on it. */
    public record Shape(String name, int cpuCoreCount, int memorySizeInGBs, boolean heatWave) {

        public Map<String, Object> toWire() {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("name", name);
            wire.put("cpuCoreCount", cpuCoreCount);
            wire.put("memorySizeInGBs", memorySizeInGBs);
            wire.put("isSupportedFor", heatWave ? List.of("DBSYSTEM", "HEATWAVECLUSTER") : List.of("DBSYSTEM"));
            return wire;
        }
    }

    /** A version family and its versions, newest last. */
    record Family(String name, String label, List<String> versions) {
    }

    static final List<Shape> SHAPES = List.of(
            // ECPU shapes: every one supports a HeatWave cluster.
            new Shape("MySQL.Free", 1, 8, true),
            new Shape("MySQL.2", 2, 16, true),
            new Shape("MySQL.4", 4, 32, true),
            new Shape("MySQL.8", 8, 64, true),
            new Shape("MySQL.16", 16, 128, true),
            new Shape("MySQL.32", 32, 256, true),
            new Shape("MySQL.48", 48, 384, true),
            new Shape("MySQL.64", 64, 512, true),
            new Shape("MySQL.96", 96, 768, true),
            new Shape("MySQL.192", 192, 1326, true),
            new Shape("MySQL.256", 256, 1024, true),
            new Shape("MySQL.384", 384, 1152, true),
            // OCPU shapes without HeatWave.
            new Shape("MySQL.VM.Standard.E3.1.8GB", 1, 8, false),
            new Shape("MySQL.VM.Standard.E3.1.16GB", 1, 16, false),
            new Shape("MySQL.VM.Standard.E3.2.32GB", 2, 32, false),
            new Shape("MySQL.VM.Standard.E3.4.64GB", 4, 64, false),
            new Shape("MySQL.VM.Standard.E3.8.128GB", 8, 128, false),
            new Shape("MySQL.VM.Standard.E3.16.256GB", 16, 256, false),
            new Shape("MySQL.VM.Standard.E4.1.8GB", 1, 8, false),
            new Shape("MySQL.VM.Standard.E4.1.16GB", 1, 16, false),
            new Shape("MySQL.VM.Standard.E4.2.32GB", 2, 32, false),
            new Shape("MySQL.VM.Standard.E4.4.64GB", 4, 64, false),
            new Shape("MySQL.VM.Standard.E4.8.128GB", 8, 128, false),
            new Shape("MySQL.VM.Standard.E4.16.256GB", 16, 256, false),
            new Shape("MySQL.VM.Standard3.1.8GB", 1, 8, false),
            new Shape("MySQL.VM.Standard3.2.32GB", 2, 32, false),
            new Shape("MySQL.VM.Standard3.4.64GB", 4, 64, false),
            // OCPU shapes with HeatWave.
            new Shape("MySQL.HeatWave.VM.Standard", 16, 512, true),
            new Shape("MySQL.HeatWave.VM.Standard.E3", 16, 512, true),
            new Shape("MySQL.HeatWave.BM.Standard", 128, 2048, true));

    static final List<Family> FAMILIES = List.of(
            new Family("8.0", "8.0 - Bug-fix release", List.of("8.0.39", "8.0.40", "8.0.41")),
            new Family("8.4", "8.4 - Long Term Support release", List.of("8.4.2", "8.4.3", "8.4.4")),
            new Family("9", "9 - Innovation release", List.of("9.1.0", "9.2.0")));

    /** What a create without {@code mysqlVersion} gets: the newest long-term-support version. */
    static final String DEFAULT_VERSION = "8.4.4";

    static final String STANDALONE = "Standalone";
    static final String HIGH_AVAILABILITY = "HA";
    private static final String DEFAULT_CONFIGURATION_TYPE = "mysqlconfiguration";

    private MysqlCatalog() {
    }

    static Optional<Shape> shape(String name) {
        return SHAPES.stream().filter(shape -> shape.name().equals(name)).findFirst();
    }

    static Shape requireShape(String name) {
        return shape(name).orElseThrow(() -> OciException.invalidParameter("Invalid shapeName: " + name));
    }

    static List<Map<String, Object>> versions() {
        List<Map<String, Object>> families = new ArrayList<>();
        for (Family family : FAMILIES) {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("versionFamily", family.label());
            wire.put("versions", family.versions().stream()
                    .map(version -> Map.<String, Object>of("version", version, "description", "MySQL " + version))
                    .toList());
            families.add(wire);
        }
        return families;
    }

    /**
     * The concrete version for a requested one: a listed version as it is, a family such as
     * {@code 8.4} as its newest version, nothing as {@link #DEFAULT_VERSION}.
     */
    static String resolveVersion(String requested) {
        if (requested == null) {
            return DEFAULT_VERSION;
        }
        for (Family family : FAMILIES) {
            if (family.name().equals(requested)) {
                return family.versions().getLast();
            }
            if (family.versions().contains(requested)) {
                return requested;
            }
        }
        throw OciException.invalidParameter("Unsupported mysqlVersion: " + requested);
    }

    /** The default configuration's display name: {@code <shape>.Standalone} or {@code <shape>.HA}. */
    static String defaultConfigurationName(String shapeName, boolean highlyAvailable) {
        return shapeName + "." + (highlyAvailable ? HIGH_AVAILABILITY : STANDALONE);
    }

    /**
     * The OCID of a default configuration. Its unique part spells out the configuration's name,
     * so it is the same on every start and resolves without a stored record. Default
     * configurations are global, like Oracle's own.
     */
    static String defaultConfigurationId(String name, String realm) {
        return "ocid1." + DEFAULT_CONFIGURATION_TYPE + "." + realm + ".."
                + HexFormat.of().formatHex(("default|" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** Every default configuration, as a Configuration body owned by {@code compartmentId}. */
    static List<Map<String, Object>> defaultConfigurations(String realm, String compartmentId, String timeCreated) {
        List<Map<String, Object>> configurations = new ArrayList<>();
        for (Shape shape : SHAPES) {
            for (boolean highlyAvailable : List.of(false, true)) {
                configurations.add(defaultConfiguration(shape.name(), highlyAvailable, realm, compartmentId,
                        timeCreated));
            }
        }
        return configurations;
    }

    static Optional<Map<String, Object>> findDefaultConfiguration(String id, String realm, String compartmentId,
                                                                  String timeCreated) {
        return defaultConfigurations(realm, compartmentId, timeCreated).stream()
                .filter(configuration -> id.equals(configuration.get("id")))
                .findFirst();
    }

    private static Map<String, Object> defaultConfiguration(String shapeName, boolean highlyAvailable, String realm,
                                                            String compartmentId, String timeCreated) {
        String name = defaultConfigurationName(shapeName, highlyAvailable);
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", defaultConfigurationId(name, realm));
        wire.put("compartmentId", compartmentId);
        wire.put("displayName", name);
        wire.put("description", "Default " + (highlyAvailable ? "high availability" : "standalone")
                + " configuration for the " + shapeName + " shape");
        wire.put("shapeName", shapeName);
        wire.put("type", "DEFAULT");
        wire.put("timeCreated", timeCreated);
        wire.put("timeUpdated", timeCreated);
        wire.put("lifecycleState", "ACTIVE");
        wire.put("options", List.of());
        wire.put("variables", Map.of());
        wire.put("freeformTags", Map.of());
        wire.put("definedTags", Map.of());
        wire.put("systemTags", Map.of());
        return wire;
    }
}
