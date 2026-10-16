package io.floci.oci.services.psql;

import io.floci.oci.core.common.OciException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The read-only catalog behind ListShapes and the default configurations, and the rules for
 * the shape and version a DB system asks for.
 *
 * <p>Oracle's SDKs carry no list of versions or shapes. {@code 14} and {@code 15} and the two
 * shapes below are the ones Oracle's own Terraform examples use; {@code 16} and {@code 17} are
 * accepted as an emulator choice so newer majors can be tried against their Postgres image.
 *
 * <p>A DB system asked for with a sized shape ({@code PostgreSQL.VM.Standard.E4.Flex.2.32GB})
 * gets a fixed default configuration, one asked for with a bare shape and separate sizes gets
 * the flexible one. Terraform rebuilds the shape name from the configuration's
 * {@code isFlexible}, so the two must agree or every plan shows a change.
 */
public final class PsqlCatalog {

    public static final String SYSTEM_TYPE = "OCI_OPTIMIZED_STORAGE";

    static final List<String> MAJORS = List.of("14", "15", "16", "17");
    static final List<String> SHAPES = List.of("VM.Standard.E4.Flex", "VM.Standard.E5.Flex");
    static final int DEFAULT_OCPU_COUNT = 2;
    static final int DEFAULT_MEMORY_GBS = 32;

    /** Terraform sends the shape as {@code PostgreSQL.<shape>} or {@code PostgreSQL.<shape>.<ocpu>.<mem>GB}. */
    private static final String SHAPE_PREFIX = "PostgreSQL.";
    private static final Pattern SIZED_SHAPE = Pattern.compile("^(.+)\\.(\\d+)\\.(\\d+)GB$");
    private static final String DEFAULT_CONFIGURATION_PREFIX = "ocid1.postgresqldefaultconfiguration.";
    private static final String FLEXIBLE = "flexible";
    private static final String FIXED = "fixed";
    private static final String CATALOG_TIME = "2023-11-14T00:00:00.000Z";

    private PsqlCatalog() {
    }

    /** A requested shape, reduced to the compute shape OCI reports and the size it carried, if any. */
    public record Shape(String name, Integer ocpuCount, Integer memorySizeInGBs) {
    }

    /** The major of {@code dbVersion} ({@code 14} or {@code 14.9}), which must be one the catalog serves. */
    public static String major(String dbVersion) {
        String major = dbVersion.contains(".") ? dbVersion.substring(0, dbVersion.indexOf('.')) : dbVersion;
        if (!MAJORS.contains(major)) {
            throw OciException.invalidParameter("Unsupported dbVersion: " + dbVersion
                    + ". Supported major versions: " + String.join(", ", MAJORS));
        }
        return major;
    }

    public static Shape parseShape(String requested) {
        String name = requested.startsWith(SHAPE_PREFIX) ? requested.substring(SHAPE_PREFIX.length()) : requested;
        Integer ocpuCount = null;
        Integer memorySizeInGBs = null;
        Matcher sized = SIZED_SHAPE.matcher(name);
        if (sized.matches()) {
            name = sized.group(1);
            ocpuCount = Integer.valueOf(sized.group(2));
            memorySizeInGBs = Integer.valueOf(sized.group(3));
        }
        if (!SHAPES.contains(name)) {
            throw OciException.invalidParameter("Unsupported shape: " + requested
                    + ". Use the /shapes API for accepted shapes.");
        }
        return new Shape(name, ocpuCount, memorySizeInGBs);
    }

    public static List<Map<String, Object>> shapes() {
        return SHAPES.stream().map(shape -> {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("id", shape);
            wire.put("shape", shape);
            wire.put("ocpuCount", DEFAULT_OCPU_COUNT);
            wire.put("memorySizeInGBs", DEFAULT_MEMORY_GBS);
            wire.put("isFlexible", true);
            return wire;
        }).toList();
    }

    /**
     * The OCID of a default configuration. Its unique part spells out what the configuration is
     * for, so it is the same on every start and can be resolved without a stored record, and
     * its type contains {@code defaultconfiguration}, which is how Terraform tells a default
     * configuration from a custom one.
     *
     * @param shape null for the flexible configuration, which fits every shape and size
     */
    public static String defaultConfigurationId(String major, Shape shape, String realm, String regionCode) {
        String key = shape == null || shape.ocpuCount() == null
                ? FLEXIBLE + "|" + major
                : FIXED + "|" + major + "|" + shape.name() + "|" + shape.ocpuCount() + "|" + shape.memorySizeInGBs();
        return DEFAULT_CONFIGURATION_PREFIX + realm + "." + regionCode + "."
                + HexFormat.of().formatHex(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The default configurations a client can browse: per major, the flexible one and a
     * fixed one for each shape at its default size. A fixed configuration for any other size
     * still resolves through {@link #findDefaultConfiguration}.
     */
    public static List<Map<String, Object>> defaultConfigurations(String realm, String regionCode) {
        List<Map<String, Object>> configurations = new ArrayList<>();
        for (String major : MAJORS) {
            configurations.add(defaultConfiguration(major, null, realm, regionCode));
            for (String shape : SHAPES) {
                configurations.add(defaultConfiguration(major,
                        new Shape(shape, DEFAULT_OCPU_COUNT, DEFAULT_MEMORY_GBS), realm, regionCode));
            }
        }
        return configurations;
    }

    /** The DefaultConfiguration body for {@code id}, when it names a version and shape the catalog serves. */
    public static Optional<Map<String, Object>> findDefaultConfiguration(String id, String realm,
                                                                         String regionCode) {
        String prefix = DEFAULT_CONFIGURATION_PREFIX + realm + "." + regionCode + ".";
        if (id == null || !id.startsWith(prefix)) {
            return Optional.empty();
        }
        String[] key;
        try {
            key = new String(HexFormat.of().parseHex(id.substring(prefix.length())), StandardCharsets.UTF_8)
                    .split("\\|");
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Shape shape = null;
        if (key.length == 5 && FIXED.equals(key[0]) && SHAPES.contains(key[2])
                && key[3].matches("\\d{1,4}") && key[4].matches("\\d{1,5}")) {
            shape = new Shape(key[2], Integer.valueOf(key[3]), Integer.valueOf(key[4]));
        } else if (key.length != 2 || !FLEXIBLE.equals(key[0])) {
            return Optional.empty();
        }
        if (!MAJORS.contains(key[1])) {
            return Optional.empty();
        }
        Map<String, Object> wire = defaultConfiguration(key[1], shape, realm, regionCode);
        wire.put("description", "Default configuration for PostgreSQL " + key[1]);
        wire.put("configurationDetails", Map.of("items", List.of()));
        return Optional.of(wire);
    }

    /** The major a default configuration is for, or empty when {@code id} is not one of the catalog's. */
    public static Optional<String> defaultConfigurationMajor(String id, String realm, String regionCode) {
        return findDefaultConfiguration(id, realm, regionCode).map(wire -> wire.get("dbVersion").toString());
    }

    private static Map<String, Object> defaultConfiguration(String major, Shape shape, String realm,
                                                            String regionCode) {
        boolean flexible = shape == null;
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", defaultConfigurationId(major, shape, realm, regionCode));
        wire.put("displayName", flexible ? "PostgreSQL." + major + ".flexible"
                : "PostgreSQL." + major + "." + shape.name() + "." + shape.ocpuCount() + "."
                        + shape.memorySizeInGBs() + "GB");
        wire.put("timeCreated", CATALOG_TIME);
        wire.put("lifecycleState", "ACTIVE");
        wire.put("dbVersion", major);
        // A configuration that fits several shapes reports "PostgreSQL" and zero sizes.
        wire.put("shape", flexible ? "PostgreSQL" : shape.name());
        wire.put("instanceOcpuCount", flexible ? 0 : shape.ocpuCount());
        wire.put("instanceMemorySizeInGBs", flexible ? 0 : shape.memorySizeInGBs());
        wire.put("isFlexible", flexible);
        wire.put("compatibleShapes", flexible ? SHAPES : List.of(shape.name()));
        return wire;
    }
}
