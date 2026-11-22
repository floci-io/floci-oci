package io.floci.oci.services.objectstorage;

import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.services.objectstorage.model.StoredLifecyclePolicy;
import io.floci.oci.services.objectstorage.model.StoredLifecycleRule;
import io.floci.oci.services.objectstorage.model.StoredObjectNameFilter;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Object lifecycle policies (PutObjectLifecyclePolicy, GetObjectLifecyclePolicy, DeleteObjectLifecyclePolicy):
 * one policy per bucket, replaced as a whole by every Put, stored in the bucket's region. Rule fields follow
 * oci-go-sdk {@code object_lifecycle_rule.go} and {@code object_name_filter.go}; limits follow Oracle's
 * Object Lifecycle Management page (usinglifecyclepolicies.htm).
 *
 * <p>The age arithmetic and the name filter live here too, for {@link ObjectStorageService#applyLifecycle}.
 */
final class BucketLifecycle {

    static final String OBJECTS = "objects";
    static final String PREVIOUS_VERSIONS = "previous-object-versions";
    static final String MULTIPART_UPLOADS = "multipart-uploads";
    static final String DELETE = "DELETE";
    static final String ABORT = "ABORT";
    /** "Each ... bucket can have a single lifecycle policy consisting of up to 1,000 rules." */
    static final int MAX_RULES = 1000;
    /** "A maximum of 20 patterns is allowed under object name filters." */
    static final int MAX_PATTERNS = 20;
    /** "Years are defined as 365.2425 days long" (object_lifecycle_rule.go). */
    static final Duration YEAR = Duration.ofSeconds(31_556_952L);

    private static final Set<String> ACTIONS = Set.of("ARCHIVE", "INFREQUENT_ACCESS", DELETE, ABORT);
    private static final Set<String> TARGETS = Set.of(OBJECTS, PREVIOUS_VERSIONS, MULTIPART_UPLOADS);
    private static final Set<String> TIME_UNITS = Set.of("DAYS", "YEARS");

    private final StorageBackend<String, StoredLifecyclePolicy> policies;

    BucketLifecycle(StorageBackend<String, StoredLifecyclePolicy> policies) {
        this.policies = policies;
    }

    StorageBackend<String, StoredLifecyclePolicy> store() {
        return policies;
    }

    synchronized StoredLifecyclePolicy put(String bucketName, Object items, String ifMatch, String ifNoneMatch) {
        List<StoredLifecycleRule> rules = parseRules(items);
        Optional<StoredLifecyclePolicy> existing = policies.get(bucketName);
        String currentEtag = existing.map(StoredLifecyclePolicy::getEtag).orElse(null);
        if (ifMatch != null && !ifMatch.isBlank() && currentEtag == null) {
            throw notFound(bucketName);
        }
        Etags.checkIfMatch(ifMatch, currentEtag);
        Etags.checkIfNoneMatch(ifNoneMatch, currentEtag);
        StoredLifecyclePolicy policy = new StoredLifecyclePolicy();
        policy.setBucketName(bucketName);
        policy.setTimeCreated(Instant.now().toString());
        policy.setEtag(Etags.newEtag());
        policy.setItems(rules);
        policies.put(bucketName, policy);
        return policy;
    }

    StoredLifecyclePolicy get(String bucketName) {
        return policies.get(bucketName).orElseThrow(() -> notFound(bucketName));
    }

    synchronized void delete(String bucketName, String ifMatch) {
        StoredLifecyclePolicy policy = get(bucketName);
        Etags.checkIfMatch(ifMatch, policy.getEtag());
        policies.delete(bucketName);
    }

    /** Drops the policy of a bucket being deleted, if it has one. */
    synchronized void forget(String bucketName) {
        policies.delete(bucketName);
    }

    private static OciException notFound(String bucketName) {
        return OciException.lifecyclePolicyNotFound(
                "No lifecycle policy exists for the bucket named '" + bucketName + "'.");
    }

    // ── Parsing and validation ──────────────────────────────────────────────────

    /** The {@code items} of a Put body. Absent or empty is a policy with no rules, which Terraform sends. */
    static List<StoredLifecycleRule> parseRules(Object items) {
        if (items == null) {
            return new ArrayList<>();
        }
        if (!(items instanceof List<?> list)) {
            throw OciException.invalidParameter("items must be a list of lifecycle rules");
        }
        if (list.size() > MAX_RULES) {
            throw OciException.invalidParameter("A lifecycle policy can have at most " + MAX_RULES + " rules");
        }
        List<StoredLifecycleRule> rules = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rule)) {
                throw OciException.invalidParameter("Each lifecycle rule must be an object");
            }
            rules.add(parseRule(rule));
        }
        return rules;
    }

    private static StoredLifecycleRule parseRule(Map<?, ?> rule) {
        StoredLifecycleRule parsed = new StoredLifecycleRule();
        parsed.setName(requiredString(rule, "name"));
        String action = requiredString(rule, "action");
        if (!ACTIONS.contains(action)) {
            throw OciException.invalidParameter("Invalid action '" + action + "'. Supported values are: "
                    + "ARCHIVE, INFREQUENT_ACCESS, DELETE, ABORT.");
        }
        parsed.setAction(action);
        Object amount = rule.get("timeAmount");
        if (amount == null) {
            throw OciException.missingParameter("timeAmount is required");
        }
        if (!(amount instanceof Integer || amount instanceof Long)) {
            throw OciException.invalidParameter("timeAmount must be a whole number");
        }
        // Oracle documents no minimum; a negative age cannot be reached, so it is rejected (inferred).
        if (((Number) amount).longValue() < 0) {
            throw OciException.invalidParameter("timeAmount cannot be negative");
        }
        parsed.setTimeAmount(((Number) amount).longValue());
        String unit = requiredString(rule, "timeUnit");
        if (!TIME_UNITS.contains(unit.toUpperCase(Locale.ROOT))) {
            throw OciException.invalidParameter("Invalid timeUnit '" + unit + "'. Supported values are: DAYS, YEARS.");
        }
        parsed.setTimeUnit(unit);
        Object enabled = rule.get("isEnabled");
        if (enabled == null) {
            throw OciException.missingParameter("isEnabled is required");
        }
        if (!(enabled instanceof Boolean flag)) {
            throw OciException.invalidParameter("isEnabled must be a boolean");
        }
        parsed.setEnabled(flag);
        Object target = rule.get("target");
        if (target != null) {
            if (!(target instanceof String value) || !TARGETS.contains(value)) {
                throw OciException.invalidParameter("Invalid target '" + target + "'. Supported values are: "
                        + "objects, previous-object-versions, multipart-uploads.");
            }
            parsed.setTarget(value);
        }
        // "multipart-uploads ... is used to specify the ABORT (only) rule" (object_lifecycle_rule.go).
        boolean uploads = MULTIPART_UPLOADS.equals(parsed.getTarget());
        if (uploads != ABORT.equals(action)) {
            throw OciException.invalidParameter("The ABORT action applies only, and always, to the "
                    + "multipart-uploads target");
        }
        Object filter = rule.get("objectNameFilter");
        if (filter != null) {
            if (!(filter instanceof Map<?, ?> filterMap)) {
                throw OciException.invalidParameter("objectNameFilter must be an object");
            }
            parsed.setObjectNameFilter(parseFilter(filterMap));
        }
        return parsed;
    }

    private static StoredObjectNameFilter parseFilter(Map<?, ?> filter) {
        StoredObjectNameFilter parsed = new StoredObjectNameFilter();
        parsed.setInclusionPrefixes(strings(filter, "inclusionPrefixes"));
        parsed.setInclusionPatterns(patterns(filter, "inclusionPatterns"));
        parsed.setExclusionPatterns(patterns(filter, "exclusionPatterns"));
        int patternCount = size(parsed.getInclusionPatterns()) + size(parsed.getExclusionPatterns());
        if (patternCount > MAX_PATTERNS) {
            throw OciException.invalidParameter("An object name filter can have at most " + MAX_PATTERNS
                    + " patterns");
        }
        return parsed;
    }

    private static List<String> patterns(Map<?, ?> filter, String key) {
        List<String> patterns = strings(filter, key);
        if (patterns != null) {
            for (String pattern : patterns) {
                try {
                    LifecycleGlob.compile(pattern);
                } catch (IllegalArgumentException e) {
                    throw OciException.invalidParameter("Invalid pattern '" + pattern + "' in " + key + ": "
                            + e.getMessage());
                }
            }
        }
        return patterns;
    }

    private static List<String> strings(Map<?, ?> filter, String key) {
        Object value = filter.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            throw OciException.invalidParameter(key + " must be a list of strings");
        }
        return list.stream().map(String.class::cast).toList();
    }

    private static String requiredString(Map<?, ?> rule, String key) {
        Object value = rule.get(key);
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw OciException.missingParameter(key + " is required");
        }
        if (!(value instanceof String s)) {
            throw OciException.invalidParameter(key + " must be a string");
        }
        return s;
    }

    private static int size(List<String> list) {
        return list == null ? 0 : list.size();
    }

    // ── Evaluation ──────────────────────────────────────────────────────────────

    /**
     * The target a rule acts on. A rule sent without one acts on objects (inferred: the Terraform provider
     * defaults the field to {@code objects}; the stored rule still reads back without it).
     */
    static String targetOf(StoredLifecycleRule rule) {
        return rule.getTarget() == null ? OBJECTS : rule.getTarget();
    }

    /**
     * Whether a resource whose age counts from {@code since} is old enough for {@code rule} at {@code now}.
     * "Days are defined as starting and ending at midnight UTC. Years are defined as 365.2425 days long and
     * likewise round up to the next midnight UTC" (object_lifecycle_rule.go); "The modification time is rounded
     * up to the nearest day in UTC" (usinglifecyclepolicies.htm). An age too large to represent is never due.
     */
    static boolean isDue(StoredLifecycleRule rule, Instant since, Instant now) {
        try {
            Duration age = "YEARS".equalsIgnoreCase(rule.getTimeUnit())
                    ? YEAR.multipliedBy(rule.getTimeAmount())
                    : Duration.ofDays(rule.getTimeAmount());
            return !now.isBefore(nextMidnight(since.plus(age)));
        } catch (ArithmeticException | DateTimeException e) {
            return false;
        }
    }

    /** {@code instant} rounded up to midnight UTC; a time already at midnight stays where it is. */
    static Instant nextMidnight(Instant instant) {
        Instant midnight = instant.atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant();
        return midnight.equals(instant) ? midnight : midnight.plus(Duration.ofDays(1));
    }

    /**
     * Whether {@code name} passes a rule's name filter. "Exclusions take precedence over inclusions", and an
     * empty inclusion list includes every object (object_name_filter.go). With both inclusion patterns and
     * inclusion prefixes, a name matching either is included (inferred: Oracle lists them in order of
     * precedence without saying how they combine).
     */
    static boolean matches(StoredLifecycleRule rule, String name) {
        StoredObjectNameFilter filter = rule.getObjectNameFilter();
        if (filter == null) {
            return true;
        }
        if (anyPattern(filter.getExclusionPatterns(), name)) {
            return false;
        }
        boolean noPatterns = size(filter.getInclusionPatterns()) == 0;
        boolean noPrefixes = size(filter.getInclusionPrefixes()) == 0;
        if (noPatterns && noPrefixes) {
            return true;
        }
        return anyPattern(filter.getInclusionPatterns(), name)
                || (!noPrefixes && filter.getInclusionPrefixes().stream().anyMatch(name::startsWith));
    }

    private static boolean anyPattern(List<String> patterns, String name) {
        return patterns != null && patterns.stream().anyMatch(p -> LifecycleGlob.compile(p).matches(name));
    }
}
