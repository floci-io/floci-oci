package io.floci.oci.core.common;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * OCI region reference data: name, 3-letter code and realm for every public region.
 *
 * <p>Transcribed from {@code oci-go-sdk/common/regions.go} ({@code shortNameRegion} and
 * {@code regionRealm}), which agrees entry for entry with the Python SDK's
 * {@code regions_definitions.py}. Refresh both together when Oracle adds regions.
 */
public final class Regions {

    /** E.g. name {@code us-ashburn-1}, code {@code iad}, realm {@code oc1}. */
    public record Region(String name, String code, String realm) {

        /** The region key as Identity reports it, e.g. {@code IAD}. */
        public String key() {
            return code.toUpperCase(Locale.ROOT);
        }
    }

    private static final List<Region> ALL = List.of(
            new Region("af-casablanca-1", "lej", "oc1"),
            new Region("af-johannesburg-1", "jnb", "oc1"),
            new Region("ap-batam-1", "hsg", "oc1"),
            new Region("ap-chuncheon-1", "yny", "oc1"),
            new Region("ap-delhi-1", "onm", "oc1"),
            new Region("ap-hyderabad-1", "hyd", "oc1"),
            new Region("ap-kulai-2", "jbp", "oc1"),
            new Region("ap-melbourne-1", "mel", "oc1"),
            new Region("ap-mumbai-1", "bom", "oc1"),
            new Region("ap-osaka-1", "kix", "oc1"),
            new Region("ap-seoul-1", "icn", "oc1"),
            new Region("ap-singapore-1", "sin", "oc1"),
            new Region("ap-singapore-2", "xsp", "oc1"),
            new Region("ap-sydney-1", "syd", "oc1"),
            new Region("ap-tokyo-1", "nrt", "oc1"),
            new Region("ca-montreal-1", "yul", "oc1"),
            new Region("ca-toronto-1", "yyz", "oc1"),
            new Region("eu-amsterdam-1", "ams", "oc1"),
            new Region("eu-frankfurt-1", "fra", "oc1"),
            new Region("eu-madrid-1", "mad", "oc1"),
            new Region("eu-madrid-3", "orf", "oc1"),
            new Region("eu-marseille-1", "mrs", "oc1"),
            new Region("eu-milan-1", "lin", "oc1"),
            new Region("eu-paris-1", "cdg", "oc1"),
            new Region("eu-stockholm-1", "arn", "oc1"),
            new Region("eu-turin-1", "nrq", "oc1"),
            new Region("eu-zurich-1", "zrh", "oc1"),
            new Region("il-jerusalem-1", "mtz", "oc1"),
            new Region("me-abudhabi-1", "auh", "oc1"),
            new Region("me-dubai-1", "dxb", "oc1"),
            new Region("me-jeddah-1", "jed", "oc1"),
            new Region("me-riyadh-1", "ruh", "oc1"),
            new Region("mx-monterrey-1", "mty", "oc1"),
            new Region("mx-queretaro-1", "qro", "oc1"),
            new Region("sa-bogota-1", "bog", "oc1"),
            new Region("sa-santiago-1", "scl", "oc1"),
            new Region("sa-saopaulo-1", "gru", "oc1"),
            new Region("sa-valparaiso-1", "vap", "oc1"),
            new Region("sa-vinhedo-1", "vcp", "oc1"),
            new Region("uk-cardiff-1", "cwl", "oc1"),
            new Region("uk-london-1", "lhr", "oc1"),
            new Region("us-ashburn-1", "iad", "oc1"),
            new Region("us-chicago-1", "ord", "oc1"),
            new Region("us-phoenix-1", "phx", "oc1"),
            new Region("us-saltlake-2", "aga", "oc1"),
            new Region("us-sanjose-1", "sjc", "oc1"),
            new Region("us-langley-1", "lfi", "oc2"),
            new Region("us-luke-1", "luf", "oc2"),
            new Region("us-gov-ashburn-1", "ric", "oc3"),
            new Region("us-gov-chicago-1", "pia", "oc3"),
            new Region("us-gov-phoenix-1", "tus", "oc3"),
            new Region("uk-gov-cardiff-1", "brs", "oc4"),
            new Region("uk-gov-london-1", "ltn", "oc4"),
            new Region("ap-chiyoda-1", "nja", "oc8"),
            new Region("ap-ibaraki-1", "ukb", "oc8"),
            new Region("me-dcc-muscat-1", "mct", "oc9"),
            new Region("me-ibri-1", "ibr", "oc9"),
            new Region("ap-dcc-canberra-1", "wga", "oc10"),
            new Region("eu-dcc-dublin-1", "ork", "oc14"),
            new Region("eu-dcc-dublin-2", "snn", "oc14"),
            new Region("eu-dcc-milan-1", "bgy", "oc14"),
            new Region("eu-dcc-milan-2", "mxp", "oc14"),
            new Region("eu-dcc-rating-1", "dus", "oc14"),
            new Region("eu-dcc-rating-2", "dtm", "oc14"),
            new Region("ap-dcc-gazipur-1", "dac", "oc15"),
            new Region("eu-frankfurt-2", "str", "oc19"),
            new Region("eu-madrid-2", "vll", "oc19"),
            new Region("eu-jovanovac-1", "beg", "oc20"),
            new Region("me-alrayyan-1", "vve", "oc21"),
            new Region("me-dcc-doha-1", "doh", "oc21"),
            new Region("us-somerset-1", "ebb", "oc23"),
            new Region("us-thames-1", "ebl", "oc23"),
            new Region("eu-crissier-1", "avf", "oc24"),
            new Region("eu-dcc-zurich-1", "avz", "oc24"),
            new Region("me-abudhabi-3", "ahu", "oc26"),
            new Region("me-alain-1", "rba", "oc26"),
            new Region("me-abudhabi-2", "rkt", "oc29"),
            new Region("me-abudhabi-4", "shj", "oc29"),
            new Region("ap-chuncheon-2", "bno", "oc35"),
            new Region("ap-seoul-2", "dtz", "oc35"),
            new Region("ap-suwon-1", "dln", "oc35"),
            new Region("us-ashburn-2", "yxj", "oc42"),
            new Region("us-newark-1", "pgc", "oc42"),
            new Region("eu-budapest-1", "jsk", "oc51"),
            new Region("sa-riodejaneiro-1", "hnw", "oc52"));

    private static final Map<String, Region> BY_NAME = ALL.stream()
            .collect(Collectors.toUnmodifiableMap(Region::name, Function.identity()));

    private Regions() {
    }

    public static Optional<Region> byName(String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }

    public static List<Region> all() {
        return ALL;
    }

    /**
     * The 3-letter code used as the region segment of regional OCIDs, e.g.
     * {@code sa-saopaulo-1 -> gru}. Names outside the table keep the legacy fallback of
     * the first three letters, so a region newer than this table still yields an OCID.
     */
    public static String code(String name) {
        return byName(name).map(Region::code)
                .orElseGet(() -> name.replaceAll("[^a-z]", "").substring(0, 3));
    }

    /** The uppercase region key, e.g. {@code IAD}. */
    public static String key(String name) {
        return code(name).toUpperCase(Locale.ROOT);
    }
}
