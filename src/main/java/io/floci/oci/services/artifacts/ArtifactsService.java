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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
        this(repositories, configurations, config, objectStorage, null);
    }

    ArtifactsService(StorageBackend<String, StoredContainerRepository> repositories,
                     StorageBackend<String, Map<String, Object>> configurations, EmulatorConfig config,
                     ObjectStorageService objectStorage, ArtifactsRegistryManager registry) {
        this.repositories = repositories;
        this.configurations = configurations;
        this.config = config;
        this.ociContext = OciContext.fromConfig(config);
        this.serviceRegistry = null;
        this.registry = registry;
        this.objectStorage = objectStorage;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("artifacts")
                .enabled(config.services().artifacts().enabled())
                .storageKey("artifacts")
                .resourceClasses(ArtifactsController.class, OcirRegistryResource.class)
                .build());
    }

    /** Stops the registry container; its volume, and so every pushed image, is kept for the next start. */
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
            registry.reset();
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

    /** The stored record goes only after every image is gone from the registry: a failed delete keeps it. */
    public synchronized void deleteRepository(String repositoryId, String ifMatch) {
        StoredContainerRepository r = getRepository(repositoryId);
        Etags.checkIfMatch(ifMatch, r.getEtag());
        for (ImageEntry image : imageEntries(r)) {
            deleteImageManifests(r, image, List.of());
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

    /**
     * Called by the data plane after a manifest push. The repository is read again here, under the
     * lock, because the push ran outside it: a repository deleted meanwhile stays deleted.
     */
    synchronized void recordPush(String repositoryId, String digest) {
        Optional<StoredContainerRepository> current = repositories.get(repositoryId);
        if (current.isEmpty()) {
            LOG.infov("Repository {0} was deleted while a manifest was being pushed; not recording {1}",
                    repositoryId, digest);
            return;
        }
        StoredContainerRepository r = current.get();
        if (digest != null && !r.getPushedDigests().contains(digest)) {
            // Copy on write: image reads walk the list outside this lock.
            List<String> pushed = new ArrayList<>(r.getPushedDigests());
            pushed.add(digest);
            r.setPushedDigests(pushed);
        }
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

    public synchronized void deleteImage(String imageId) {
        for (StoredContainerRepository r : repositories.scan(k -> true)) {
            List<ImageEntry> images = imageEntries(r);
            for (ImageEntry image : images) {
                if (imageId.equals(image.wire().get("id"))) {
                    deleteImageManifests(r, image, images.stream().filter(other -> other != image).toList());
                    return;
                }
            }
        }
        throw OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: image " + imageId);
    }

    /**
     * Deletes an image's manifest, and the platform manifests of an image index with it, then forgets
     * their digests. A platform manifest that one of {@code remaining} also lists is kept, since two
     * releases of a multi-platform image can share one. A registry failure is raised, so nothing is
     * reported deleted that still exists.
     */
    private void deleteImageManifests(StoredContainerRepository r, ImageEntry image, List<ImageEntry> remaining) {
        String digest = image.digest();
        deleteManifest(r, digest);
        Set<String> shared = new HashSet<>();
        for (ImageEntry other : remaining) {
            shared.addAll(other.childDigests());
        }
        List<String> children = image.childDigests().stream().filter(child -> !shared.contains(child)).toList();
        for (String child : children) {
            deleteManifest(r, child);
        }
        repositories.get(r.getId()).ifPresent(stored -> {
            List<String> pushed = new ArrayList<>(stored.getPushedDigests());
            pushed.remove(digest);
            pushed.removeAll(children);
            stored.setPushedDigests(pushed);
            repositories.put(stored.getId(), stored);
        });
    }

    /**
     * One entry per distinct manifest digest in the repository, with the tags that point at it as
     * versions. Digests reached through tags and digests recorded at push time both count, so an image
     * whose tag moved to a newer push is still listed, untagged. The platform manifests of an image
     * index are part of that index, not images of their own. Empty in mock mode, and while the
     * registry has not been started for a repository that has never had a push.
     */
    List<Map<String, Object>> images(StoredContainerRepository r) {
        return imageEntries(r).stream().map(ImageEntry::wire).toList();
    }

    /** An image's wire shape, with the platform manifest digests an index deletes along with it. */
    private record ImageEntry(String digest, Map<String, Object> wire, List<String> childDigests) {
    }

    private List<ImageEntry> imageEntries(StoredContainerRepository r) {
        if (mock() || registry == null) {
            return List.of();
        }
        List<String> pushedDigests = List.copyOf(r.getPushedDigests());
        if (!registry.isStarted()) {
            if (pushedDigests.isEmpty()) {
                return List.of();
            }
            registry.ensureReady();
        }
        String backendName = backendRepository(r);
        Map<String, Map<String, Object>> byDigest = new LinkedHashMap<>();
        Map<String, Manifest> manifests = new LinkedHashMap<>();
        for (String tag : tags(backendName)) {
            Optional<Manifest> manifest = manifest(backendName, tag);
            if (manifest.isEmpty()) {
                continue;
            }
            manifests.putIfAbsent(manifest.get().digest(), manifest.get());
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
        for (String digest : pushedDigests) {
            if (byDigest.containsKey(digest)) {
                continue;
            }
            manifest(backendName, digest).ifPresent(manifest -> {
                manifests.put(manifest.digest(), manifest);
                byDigest.put(manifest.digest(), newImage(r, manifest));
            });
        }
        for (Manifest manifest : manifests.values()) {
            manifest.childDigests().forEach(byDigest::remove);
        }
        List<ImageEntry> entries = new ArrayList<>();
        byDigest.forEach((digest, wire) -> entries.add(new ImageEntry(digest, wire,
                manifests.get(digest).childDigests())));
        return entries;
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

    /**
     * A manifest as the registry stores it. For an image index (or Docker manifest list) the layers
     * and their size are those of its platform manifests, whose digests are {@code childDigests}.
     */
    private record Manifest(String digest, long size, List<Map<String, Object>> layers, long layersSize,
                            List<String> childDigests) {
    }

    /** Tags of a repository; a repository the registry has never seen (404) has none. */
    private List<String> tags(String backendName) {
        HttpResponse<byte[]> response = registryCall("GET", "/v2/" + backendName + "/tags/list", null);
        if (response.statusCode() == 404) {
            return List.of();
        }
        requireSuccess(response, "list the tags of " + backendName);
        List<String> tags = new ArrayList<>();
        try {
            mapper.readTree(response.body()).path("tags").forEach(t -> tags.add(t.asText()));
        } catch (IOException e) {
            throw invalidResponse("The registry returned an unreadable tag list for " + backendName, e);
        }
        return tags;
    }

    /** A manifest by tag or digest; one that no longer exists (404) is empty. */
    private Optional<Manifest> manifest(String backendName, String reference) {
        return manifest(backendName, reference, true);
    }

    private Optional<Manifest> manifest(String backendName, String reference, boolean followIndex) {
        HttpResponse<byte[]> response = registryCall("GET", "/v2/" + backendName + "/manifests/" + reference,
                MANIFEST_ACCEPT);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        requireSuccess(response, "read manifest " + backendName + ":" + reference);
        String digest = response.headers().firstValue("Docker-Content-Digest").orElse(null);
        if (digest == null) {
            throw invalidResponse("The registry returned manifest " + backendName + ":" + reference
                    + " without a Docker-Content-Digest header", null);
        }
        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (IOException e) {
            throw invalidResponse("The registry returned an unreadable manifest " + backendName + ":" + reference, e);
        }
        List<Map<String, Object>> layers = new ArrayList<>();
        List<String> children = new ArrayList<>();
        long total = 0;
        for (JsonNode layer : root.path("layers")) {
            Map<String, Object> l = new LinkedHashMap<>();
            long size = layer.path("size").asLong();
            l.put("digest", layer.path("digest").asText());
            l.put("sizeInBytes", size);
            l.put("timeCreated", Instant.now().toString());
            layers.add(l);
            total += size;
        }
        if (followIndex) {
            for (JsonNode child : root.path("manifests")) {
                String childDigest = child.path("digest").asText(null);
                if (childDigest == null) {
                    continue;
                }
                children.add(childDigest);
                Optional<Manifest> platform = manifest(backendName, childDigest, false);
                if (platform.isPresent()) {
                    layers.addAll(platform.get().layers());
                    total += platform.get().layersSize();
                }
            }
        }
        return Optional.of(new Manifest(digest, response.body().length, layers, total, children));
    }

    /** Deletes one manifest. Already gone (404) is success; any other failure is raised. */
    private void deleteManifest(StoredContainerRepository r, String digest) {
        if (mock() || registry == null || digest == null) {
            return;
        }
        if (!registry.isStarted()) {
            registry.ensureReady();
        }
        HttpResponse<byte[]> response = registryCall("DELETE",
                "/v2/" + backendRepository(r) + "/manifests/" + digest, null);
        if (response.statusCode() != 404) {
            requireSuccess(response, "delete manifest " + digest + " of " + r.getDisplayName());
        }
    }

    /** A call to the registry sidecar; a connection failure is {@code 503 ExternalServerUnreachable}. */
    private HttpResponse<byte[]> registryCall(String method, String path, String accept) {
        try {
            return send(method, path, accept);
        } catch (IOException e) {
            throw new OciException("ExternalServerUnreachable",
                    "The OCIR registry could not be reached: " + e.getMessage(), 503);
        }
    }

    private static void requireSuccess(HttpResponse<byte[]> response, String action) {
        if (response.statusCode() / 100 != 2) {
            throw invalidResponse("The OCIR registry could not " + action + ": HTTP " + response.statusCode(), null);
        }
    }

    private static OciException invalidResponse(String message, Exception cause) {
        if (cause != null) {
            LOG.warnv(cause, "{0}", message);
        }
        return new OciException("ExternalServerInvalidResponse", message, 503);
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
