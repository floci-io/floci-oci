package io.floci.oci.services.artifacts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.Resettable;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.services.artifacts.model.StoredContainerRepository;
import io.floci.oci.services.objectstorage.ObjectStorageService;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * OCI Container Registry (OCIR): the Artifacts management plane ({@code /20160918/container/...},
 * oci-go-sdk/artifacts) over repositories floci-oci stores, and images read live from the shared
 * {@code registry:2} sidecar the {@code /v2/} data plane pushes into.
 *
 * <p>The sidecar is shared by every tenancy and region; a repository {@code project/app} of
 * namespace {@code ns} in {@code us-ashburn-1} lives at {@code iad/ns/project/app} inside it.
 * Every container interaction sits behind {@code !mock()}; in mock mode repositories and the
 * container configuration still work and image lists are empty.
 */
@ApplicationScoped
public class ArtifactsService implements Resettable, CompartmentUsage {

    private static final Logger LOG = Logger.getLogger(ArtifactsService.class);

    /** Docker Distribution's repository name grammar: lowercase components joined by '/'. */
    private static final Pattern REPOSITORY_NAME =
            Pattern.compile("^[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*(?:/[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*)*$");
    private static final String MANIFEST_ACCEPT = String.join(", ",
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.docker.distribution.manifest.v2+json");
    private static final String CONFIGURATION_KEY = "configuration";
    private static final long GIGABYTE = 1024L * 1024L * 1024L;

    private final StorageBackend<String, StoredContainerRepository> repositories;
    private final StorageBackend<String, Map<String, Object>> configurations;
    private final EmulatorConfig config;
    private final OciContext ociContext;
    private final ServiceRegistry serviceRegistry;
    private final ArtifactsRegistryManager registry;
    private final ObjectStorageService objectStorage;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public ArtifactsService(StorageFactory storageFactory, EmulatorConfig config, ServiceRegistry serviceRegistry,
                            OciContext ociContext, ArtifactsRegistryManager registry,
                            ObjectStorageService objectStorage) {
        this.config = config;
        this.ociContext = ociContext;
        this.serviceRegistry = serviceRegistry;
        this.registry = registry;
        this.objectStorage = objectStorage;
        this.repositories = storageFactory.create("artifacts", "artifacts-repositories.json",
                new TypeReference<Map<String, StoredContainerRepository>>() {});
        this.configurations = storageFactory.create("artifacts", "artifacts-configuration.json",
                new TypeReference<Map<String, Map<String, Object>>>() {});
    }

    ArtifactsService(StorageBackend<String, StoredContainerRepository> repositories,
                     StorageBackend<String, Map<String, Object>> configurations, EmulatorConfig config,
                     ObjectStorageService objectStorage) {
        this.repositories = repositories;
        this.configurations = configurations;
        this.config = config;
        this.ociContext = OciContext.fromConfig(config);
        this.serviceRegistry = null;
        this.registry = null;
        this.objectStorage = objectStorage;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("artifacts")
                .enabled(config.services().artifacts().enabled())
                .storageKey("artifacts")
                .resourceClasses(ArtifactsController.class, OcirRegistryResource.class)
                .build());
    }

    @PreDestroy
    void shutdown() {
        if (!mock()) {
            registry.stop();
        }
    }

    /** {@code POST /_floci-oci/state/reset} also removes the registry container and its volume. */
    @Override
    public void clear() {
        repositories.clear();
        configurations.clear();
        if (!mock()) {
            registry.stop();
        }
    }

    boolean mock() {
        return config.services().artifacts().mock();
    }

    // ── Repositories ───────────────────────────────────────────────────────────

    public synchronized StoredContainerRepository createRepository(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String compartmentId = requireString(b, "compartmentId");
        String displayName = requireString(b, "displayName");
        return create(compartmentId, displayName, bool(b, "isPublic"), bool(b, "isImmutable"), readme(b),
                stringMap(b.get("freeformTags")), definedTags(b.get("definedTags")));
    }

    private StoredContainerRepository create(String compartmentId, String displayName, boolean isPublic,
                                             boolean immutable, Map<String, Object> readme,
                                             Map<String, String> freeformTags,
                                             Map<String, Map<String, Object>> definedTags) {
        if (!REPOSITORY_NAME.matcher(displayName).matches()) {
            throw OciException.invalidParameter("Invalid repository name " + displayName
                    + ": use lowercase letters, digits, '.', '_', '-' and '/' separators");
        }
        if (findByName(displayName).isPresent()) {
            throw new OciException("NotAuthorizedOrResourceAlreadyExists",
                    "A repository named " + displayName + " already exists in this region", 409);
        }
        StoredContainerRepository r = new StoredContainerRepository();
        r.setId(Ocids.generate("containerrepo", ociContext.realm(), ociContext.regionCode()));
        r.setCompartmentId(compartmentId);
        r.setDisplayName(displayName);
        r.setNamespace(namespace());
        r.setCreatedBy(ociContext.userIdOrTenancy());
        r.setPublicRepository(isPublic);
        r.setImmutable(immutable);
        r.setReadme(readme);
        r.setLifecycleState("AVAILABLE");
        r.setTimeCreated(Instant.now().toString());
        r.setEtag(Etags.newEtag());
        r.setFreeformTags(freeformTags);
        r.setDefinedTags(definedTags);
        repositories.put(r.getId(), r);
        LOG.infov("createContainerRepository {0} ({1})", displayName, r.getId());
        return r;
    }

    public StoredContainerRepository getRepository(String repositoryId) {
        return repositories.get(repositoryId)
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: repository " + repositoryId));
    }

    public Optional<StoredContainerRepository> findByName(String displayName) {
        return repositories.scan(k -> true).stream()
                .filter(r -> displayName.equals(r.getDisplayName()))
                .findFirst();
    }

    public List<StoredContainerRepository> listRepositories(String compartmentId, boolean inSubtree,
                                                            String repositoryId, String displayName,
                                                            Boolean isPublic, String lifecycleState,
                                                            String sortBy, String sortOrder) {
        boolean wholeTenancy = inSubtree && compartmentId.equals(ociContext.tenancyId());
        Comparator<StoredContainerRepository> order = "DISPLAYNAME".equals(sortBy)
                ? Comparator.comparing(StoredContainerRepository::getDisplayName)
                : Comparator.comparing(StoredContainerRepository::getTimeCreated);
        boolean descending = sortOrder == null ? !"DISPLAYNAME".equals(sortBy) : "DESC".equals(sortOrder);
        if (descending) {
            order = order.reversed();
        }
        return repositories.scan(k -> true).stream()
                .filter(r -> wholeTenancy || compartmentId.equals(r.getCompartmentId()))
                .filter(r -> repositoryId == null || repositoryId.equals(r.getId()))
                .filter(r -> displayName == null || displayName.equals(r.getDisplayName()))
                .filter(r -> isPublic == null || isPublic == r.isPublicRepository())
                .filter(r -> lifecycleState == null || lifecycleState.equals(r.getLifecycleState()))
                .sorted(order)
                .toList();
    }

    public synchronized StoredContainerRepository updateRepository(String repositoryId, Map<String, Object> body,
                                                                   String ifMatch) {
        StoredContainerRepository r = getRepository(repositoryId);
        Etags.checkIfMatch(ifMatch, r.getEtag());
        Map<String, Object> b = body == null ? Map.of() : body;
        if (b.get("isPublic") instanceof Boolean isPublic) {
            r.setPublicRepository(isPublic);
        }
        if (b.get("isImmutable") instanceof Boolean immutable) {
            r.setImmutable(immutable);
        }
        if (b.get("readme") instanceof Map<?, ?>) {
            r.setReadme(readme(b));
        }
        if (b.get("freeformTags") instanceof Map<?, ?> tags) {
            r.setFreeformTags(stringMap(tags));
        }
        if (b.get("definedTags") instanceof Map<?, ?> tags) {
            r.setDefinedTags(definedTags(tags));
        }
        r.setEtag(Etags.newEtag());
        repositories.put(repositoryId, r);
        return r;
    }

    public synchronized void deleteRepository(String repositoryId, String ifMatch) {
        StoredContainerRepository r = getRepository(repositoryId);
        Etags.checkIfMatch(ifMatch, r.getEtag());
        for (Map<String, Object> image : images(r)) {
            deleteManifest(r, (String) image.get("digest"));
        }
        repositories.delete(repositoryId);
    }

    public synchronized StoredContainerRepository changeCompartment(String repositoryId, String compartmentId,
                                                                    String ifMatch) {
        StoredContainerRepository r = getRepository(repositoryId);
        Etags.checkIfMatch(ifMatch, r.getEtag());
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        r.setCompartmentId(compartmentId);
        r.setEtag(Etags.newEtag());
        repositories.put(repositoryId, r);
        return r;
    }

    /** Called by the data plane after a manifest push. */
    synchronized void recordPush(StoredContainerRepository r) {
        r.setTimeLastPushed(Instant.now().toString());
        repositories.put(r.getId(), r);
    }

    /**
     * The repository a push lands in. When the repository does not exist and the tenancy's
     * container configuration has {@code isRepositoryCreatedOnFirstPush}, a private repository is
     * created in the root compartment (Registry/Tasks/registrycreatingarepository.htm).
     */
    synchronized Optional<StoredContainerRepository> repositoryForPush(String name) {
        Optional<StoredContainerRepository> existing = findByName(name);
        if (existing.isPresent() || !Boolean.TRUE.equals(configuration().get("isRepositoryCreatedOnFirstPush"))) {
            return existing;
        }
        return Optional.of(create(ociContext.tenancyId(), name, false, false, null, new LinkedHashMap<>(),
                new LinkedHashMap<>()));
    }

    // ── Container configuration ────────────────────────────────────────────────

    public Map<String, Object> configuration() {
        Map<String, Object> wire = new LinkedHashMap<>();
        Map<String, Object> stored = configurations.get(CONFIGURATION_KEY).orElse(Map.of());
        wire.put("isRepositoryCreatedOnFirstPush",
                Boolean.TRUE.equals(stored.get("isRepositoryCreatedOnFirstPush")));
        wire.put("namespace", namespace());
        return wire;
    }

    public synchronized Map<String, Object> updateConfiguration(Map<String, Object> body) {
        Map<String, Object> stored = new LinkedHashMap<>(configurations.get(CONFIGURATION_KEY).orElse(Map.of()));
        if (body != null && body.get("isRepositoryCreatedOnFirstPush") instanceof Boolean createOnPush) {
            stored.put("isRepositoryCreatedOnFirstPush", createOnPush);
        }
        stored.put("namespace", namespace());
        configurations.put(CONFIGURATION_KEY, stored);
        return configuration();
    }

    // ── Images, read from the registry ─────────────────────────────────────────

    public List<Map<String, Object>> listImages(String compartmentId, boolean inSubtree, String repositoryId,
                                                String repositoryName, String displayName, String imageId,
                                                String version, String imageDigest, Boolean isVersioned) {
        List<Map<String, Object>> images = new ArrayList<>();
        for (StoredContainerRepository r : listRepositories(compartmentId, inSubtree, repositoryId, null, null,
                null, "DISPLAYNAME", "ASC")) {
            if (repositoryName != null && !repositoryName.equals(r.getDisplayName())) {
                continue;
            }
            images.addAll(images(r));
        }
        return images.stream()
                .filter(i -> displayName == null || displayName.equals(i.get("displayName")))
                .filter(i -> imageId == null || imageId.equals(i.get("id")))
                .filter(i -> imageDigest == null || imageDigest.equals(i.get("digest")))
                .filter(i -> version == null || versionNames(i).contains(version))
                .filter(i -> isVersioned == null || isVersioned == !versionNames(i).isEmpty())
                .toList();
    }

    public Map<String, Object> getImage(String imageId) {
        return repositories.scan(k -> true).stream()
                .flatMap(r -> images(r).stream())
                .filter(i -> imageId.equals(i.get("id")))
                .findFirst()
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "Authorization failed or requested resource not found: image " + imageId));
    }

    public void deleteImage(String imageId) {
        Map<String, Object> image = getImage(imageId);
        deleteManifest(getRepository((String) image.get("repositoryId")), (String) image.get("digest"));
    }

    /**
     * One entry per distinct manifest digest in the repository, with the tags that point at it as
     * versions. Empty in mock mode or while the registry has not been started by a push.
     */
    List<Map<String, Object>> images(StoredContainerRepository r) {
        if (mock() || registry == null || !registry.isStarted()) {
            return List.of();
        }
        String backendName = backendRepository(r);
        Map<String, Map<String, Object>> byDigest = new LinkedHashMap<>();
        for (String tag : tags(backendName)) {
            Optional<Manifest> manifest = manifest(backendName, tag);
            if (manifest.isEmpty()) {
                continue;
            }
            Map<String, Object> image = byDigest.computeIfAbsent(manifest.get().digest(), d -> newImage(r, manifest.get()));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> versions = (List<Map<String, Object>>) image.get("versions");
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("createdBy", r.getCreatedBy());
            v.put("timeCreated", Objects.requireNonNullElse(r.getTimeLastPushed(), r.getTimeCreated()));
            v.put("version", tag);
            versions.add(v);
            if (image.get("version") == null) {
                image.put("version", tag);
                image.put("displayName", r.getDisplayName() + ":" + tag);
            }
        }
        return new ArrayList<>(byDigest.values());
    }

    private Map<String, Object> newImage(StoredContainerRepository r, Manifest manifest) {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("compartmentId", r.getCompartmentId());
        image.put("createdBy", r.getCreatedBy());
        image.put("digest", manifest.digest());
        image.put("displayName", r.getDisplayName() + ":unknown@" + manifest.digest());
        image.put("id", imageId(r, manifest.digest()));
        image.put("layers", manifest.layers());
        image.put("layersSizeInBytes", manifest.layersSize());
        image.put("lifecycleState", "AVAILABLE");
        image.put("manifestSizeInBytes", manifest.size());
        image.put("pullCount", 0);
        image.put("repositoryId", r.getId());
        image.put("repositoryName", r.getDisplayName());
        image.put("timeCreated", Objects.requireNonNullElse(r.getTimeLastPushed(), r.getTimeCreated()));
        image.put("versions", new ArrayList<Map<String, Object>>());
        image.put("version", null);
        image.put("freeformTags", Map.of());
        image.put("definedTags", Map.of());
        image.put("systemTags", Map.of());
        return image;
    }

    /** Repository totals for the wire: image count and layer sizes, all zero before anything was pushed. */
    public RepositoryStats stats(StoredContainerRepository r) {
        List<Map<String, Object>> images = images(r);
        long layers = 0;
        long bytes = 0;
        for (Map<String, Object> image : images) {
            layers += ((List<?>) image.get("layers")).size();
            bytes += ((Number) image.get("layersSizeInBytes")).longValue();
        }
        return new RepositoryStats(images.size(), layers, bytes, (bytes + GIGABYTE - 1) / GIGABYTE);
    }

    public record RepositoryStats(int imageCount, long layerCount, long layersSizeInBytes, long billableSizeInGBs) {
    }

    private record Manifest(String digest, long size, List<Map<String, Object>> layers, long layersSize) {
    }

    private List<String> tags(String backendName) {
        try {
            HttpResponse<byte[]> response = send("GET", "/v2/" + backendName + "/tags/list", null);
            if (response.statusCode() != 200) {
                return List.of();
            }
            List<String> tags = new ArrayList<>();
            mapper.readTree(response.body()).path("tags").forEach(t -> tags.add(t.asText()));
            return tags;
        } catch (IOException e) {
            LOG.debugv("Could not list tags of {0}: {1}", backendName, e.getMessage());
            return List.of();
        }
    }

    private Optional<Manifest> manifest(String backendName, String reference) {
        try {
            HttpResponse<byte[]> response = send("GET", "/v2/" + backendName + "/manifests/" + reference,
                    MANIFEST_ACCEPT);
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            String digest = response.headers().firstValue("Docker-Content-Digest").orElse(null);
            if (digest == null) {
                return Optional.empty();
            }
            List<Map<String, Object>> layers = new ArrayList<>();
            long total = 0;
            JsonNode root = mapper.readTree(response.body());
            for (JsonNode layer : root.path("layers")) {
                Map<String, Object> l = new LinkedHashMap<>();
                long size = layer.path("size").asLong();
                l.put("digest", layer.path("digest").asText());
                l.put("sizeInBytes", size);
                l.put("timeCreated", Instant.now().toString());
                layers.add(l);
                total += size;
            }
            return Optional.of(new Manifest(digest, response.body().length, layers, total));
        } catch (IOException e) {
            LOG.debugv("Could not read manifest {0}:{1}: {2}", backendName, reference, e.getMessage());
            return Optional.empty();
        }
    }

    private void deleteManifest(StoredContainerRepository r, String digest) {
        if (mock() || registry == null || !registry.isStarted() || digest == null) {
            return;
        }
        try {
            send("DELETE", "/v2/" + backendRepository(r) + "/manifests/" + digest, null);
        } catch (IOException e) {
            LOG.warnv("Could not delete manifest {0} of {1}: {2}", digest, r.getDisplayName(), e.getMessage());
        }
    }

    private HttpResponse<byte[]> send(String method, String path, String accept) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://" + registry.endpoint() + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (accept != null) {
            request.header("Accept", accept);
        }
        try {
            return registry.httpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    // ── Data plane support ─────────────────────────────────────────────────────

    /** {@code <regionCode>/<namespace>/<repository>}: the repository's path inside the shared registry. */
    String backendRepository(StoredContainerRepository r) {
        return backendRepository(r.getNamespace(), r.getDisplayName());
    }

    String backendRepository(String namespace, String repositoryName) {
        return ociContext.regionCode().toLowerCase(Locale.ROOT) + "/" + namespace + "/" + repositoryName;
    }

    /**
     * The tenancy whose namespace is {@code namespace}, from the repositories and container
     * configurations stored across every tenancy. Namespaces are derived one way from the tenancy,
     * so a tenancy is known to the data plane once it has used the management plane.
     */
    Optional<String> tenancyOfNamespace(String namespace) {
        if (namespace.equals(objectStorage.namespaceOf(config.defaultTenancyId()))) {
            return Optional.of(config.defaultTenancyId());
        }
        for (Map.Entry<String, Map<String, StoredContainerRepository>> partition
                : TenancyAwareStorageBackend.entriesByPartition(repositories).entrySet()) {
            String tenancy = TenancyAwareStorageBackend.tenancyOf(partition.getKey());
            if (namespace.equals(objectStorage.namespaceOf(tenancy))) {
                return Optional.of(tenancy);
            }
        }
        for (String partition : TenancyAwareStorageBackend.entriesByPartition(configurations).keySet()) {
            String tenancy = TenancyAwareStorageBackend.tenancyOf(partition);
            if (namespace.equals(objectStorage.namespaceOf(tenancy))) {
                return Optional.of(tenancy);
            }
        }
        return Optional.empty();
    }

    ArtifactsRegistryManager registry() {
        return registry;
    }

    /**
     * A stable image OCID derived from the repository and the manifest digest, so it survives
     * restarts without a stored record and Terraform state keeps pointing at the same image.
     */
    String imageId(StoredContainerRepository r, String digest) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((r.getId() + "@" + digest).getBytes(StandardCharsets.UTF_8));
            return "ocid1.containerimage." + ociContext.realm() + "." + ociContext.regionCode().toLowerCase(Locale.ROOT)
                    + "." + "0." + HexFormat.of().formatHex(hash, 0, 30);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private String namespace() {
        return objectStorage.namespaceOf(ociContext.tenancyId());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<String> versionNames(Map<String, Object> image) {
        return ((List<Map<String, Object>>) image.get("versions")).stream()
                .map(v -> (String) v.get("version")).toList();
    }

    private static Map<String, Object> readme(Map<String, Object> b) {
        if (!(b.get("readme") instanceof Map<?, ?> raw)) {
            return null;
        }
        Object content = raw.get("content");
        Object format = raw.get("format");
        if (!(content instanceof String) || !(format instanceof String f)) {
            throw OciException.invalidParameter("readme needs content and format");
        }
        if (!"TEXT_MARKDOWN".equals(f) && !"TEXT_PLAIN".equals(f)) {
            throw OciException.invalidParameter("readme format must be TEXT_MARKDOWN or TEXT_PLAIN");
        }
        Map<String, Object> readme = new LinkedHashMap<>();
        readme.put("content", content);
        readme.put("format", f);
        return readme;
    }

    private static String requireString(Map<String, Object> b, String key) {
        String value = str(b, key);
        if (value == null) {
            throw OciException.missingParameter(key + " is required");
        }
        return value;
    }

    static String str(Map<String, Object> b, String key) {
        return b != null && b.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    private static boolean bool(Map<String, Object> b, String key) {
        return b.get(key) instanceof Boolean value && value;
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> result.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> definedTags(Object value) {
        return value instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Map<String, Object>>) map) : new LinkedHashMap<>();
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return CompartmentUsage.live(repositories, tenancyId, compartmentId,
                StoredContainerRepository::getCompartmentId, StoredContainerRepository::getLifecycleState,
                r -> "container repository " + r.getId()).toList();
    }
}
