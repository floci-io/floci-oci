package io.floci.oci.services.artifacts;

import io.floci.oci.core.common.Regions;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCIR registry host names: {@code <region-key>.ocir.<domain>} ({@code iad.ocir.io}), a region
 * identifier in place of the key ({@code us-ashburn-1.ocir.io}), or {@code ocir.<region>.<domain>}.
 */
final class OcirHosts {

    private static final Pattern REGION_FIRST = Pattern.compile("^([a-z0-9-]+)\\.ocir\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern OCIR_FIRST = Pattern.compile("^ocir\\.([a-z0-9-]+)\\.", Pattern.CASE_INSENSITIVE);

    private OcirHosts() {
    }

    /** The region a registry host names: a region key ({@code iad}) or identifier ({@code us-ashburn-1}). */
    static Optional<String> regionOf(String host) {
        String bare = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        for (Pattern pattern : List.of(REGION_FIRST, OCIR_FIRST)) {
            Matcher m = pattern.matcher(bare);
            if (m.find()) {
                String label = m.group(1).toLowerCase(Locale.ROOT);
                Optional<String> region = Regions.byName(label).map(Regions.Region::name)
                        .or(() -> Regions.byCode(label).map(Regions.Region::name));
                if (region.isPresent()) {
                    return region;
                }
            }
        }
        return Optional.empty();
    }
}
