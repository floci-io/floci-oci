package io.floci.oci.services.containerinstances;

import io.floci.oci.core.common.OciException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The flexible container instance shapes, from Oracle's Container Instances Shapes page
 * (https://docs.oracle.com/en-us/iaas/Content/container-instances/container-instance-shapes.htm):
 * the OCPU range, the memory range (at least 1 GB or one per OCPU, at most 64 GB per OCPU up to a
 * per-shape total), 1 Gbps per OCPU up to a per-shape cap, and the regions CI.Standard.E5.Flex is
 * offered in. The processor descriptions and the default memory per OCPU are not on that page;
 * they follow the Compute shapes of the same series and are inferred.
 */
final class ContainerInstancesCatalog {

    record Shape(String name, String processorDescription, int maxOcpus, int maxMemoryInGBs,
                 int maxBandwidthInGbps, int defaultMemoryPerOcpuInGBs, Set<String> regions) {

        boolean offeredIn(String region) {
            return regions.isEmpty() || regions.contains(region);
        }

        Map<String, Object> toWire() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("name", name);
            json.put("processorDescription", processorDescription);
            json.put("ocpuOptions", Map.of("min", 1.0, "max", (double) maxOcpus));
            Map<String, Object> memory = new LinkedHashMap<>();
            memory.put("minInGBs", 1.0);
            memory.put("maxInGBs", (double) maxMemoryInGBs);
            memory.put("defaultPerOcpuInGBs", (double) defaultMemoryPerOcpuInGBs);
            memory.put("minPerOcpuInGBs", 1.0);
            memory.put("maxPerOcpuInGBs", 64.0);
            json.put("memoryOptions", memory);
            Map<String, Object> bandwidth = new LinkedHashMap<>();
            bandwidth.put("minInGbps", 1.0);
            bandwidth.put("maxInGbps", (double) maxBandwidthInGbps);
            bandwidth.put("defaultPerOcpuInGbps", 1.0);
            json.put("networkingBandwidthOptions", bandwidth);
            return json;
        }
    }

    private static final Set<String> E5_REGIONS = Set.of("us-dallas-1", "ap-batam-1", "us-abilene-1",
            "ap-kulai-1", "eu-dublin-3", "us-boardman-1", "us-desmoines-1", "us-quincy-1", "us-shawnee-1",
            "eu-madrid-3", "ap-delhi-1", "ap-chennai-1", "eu-turin-1", "us-columbus-1", "sa-riodejaneiro-2",
            "ap-kulai-2", "af-casablanca-1");

    static final List<Shape> SHAPES = List.of(
            new Shape("CI.Standard.E4.Flex", "2.55 GHz AMD EPYC 7J13 (Milan)", 64, 1024, 40, 16, Set.of()),
            new Shape("CI.Standard.E5.Flex", "2.6 GHz AMD EPYC 9J14 (Genoa)", 94, 1504, 100, 16, E5_REGIONS),
            new Shape("CI.Standard.A1.Flex", "3.0 GHz Ampere Altra", 76, 488, 40, 6, Set.of()));

    private ContainerInstancesCatalog() {
    }

    static List<Shape> shapesIn(String region) {
        return SHAPES.stream().filter(shape -> shape.offeredIn(region)).toList();
    }

    static Optional<Shape> shape(String name, String region) {
        return shapesIn(region).stream().filter(shape -> shape.name().equals(name)).findFirst();
    }

    /**
     * The ContainerInstanceShapeConfig a create resolves to: the requested OCPUs and memory, the
     * memory defaulting to the shape's per-OCPU default, and the bandwidth the OCPUs earn.
     */
    static Map<String, Object> shapeConfig(Shape shape, Map<String, Object> requested) {
        Object ocpusValue = requested.get("ocpus");
        if (!(ocpusValue instanceof Number ocpusNumber)) {
            throw OciException.missingParameter("Missing required parameter: shapeConfig.ocpus");
        }
        double ocpus = ocpusNumber.doubleValue();
        if (ocpus < 1 || ocpus > shape.maxOcpus()) {
            throw OciException.invalidParameter("shapeConfig.ocpus must be between 1 and " + shape.maxOcpus()
                    + " for " + shape.name() + ": " + ocpusValue);
        }
        double memory;
        Object memoryValue = requested.get("memoryInGBs");
        if (memoryValue == null) {
            memory = Math.min(ocpus * shape.defaultMemoryPerOcpuInGBs(), shape.maxMemoryInGBs());
        } else if (memoryValue instanceof Number memoryNumber) {
            memory = memoryNumber.doubleValue();
        } else {
            throw OciException.invalidParameter("shapeConfig.memoryInGBs must be a number: " + memoryValue);
        }
        double maxMemory = Math.min(ocpus * 64, shape.maxMemoryInGBs());
        if (memory < Math.max(1, ocpus) || memory > maxMemory) {
            throw OciException.invalidParameter("shapeConfig.memoryInGBs must be between " + Math.max(1, ocpus)
                    + " and " + maxMemory + " for " + ocpus + " OCPUs of " + shape.name() + ": " + memoryValue);
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("ocpus", ocpus);
        config.put("memoryInGBs", memory);
        config.put("processorDescription", shape.processorDescription());
        config.put("networkingBandwidthInGbps", Math.min(ocpus, shape.maxBandwidthInGbps()));
        return config;
    }
}
