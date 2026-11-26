package io.floci.oci.services.containerinstances;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.ContainerImageResolver;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.NetworkReferences;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.RequestContextPropagation;
import io.floci.oci.core.common.Resettable;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.containerinstances.ContainerInstancesRequests.ContainerRequest;
import io.floci.oci.services.containerinstances.model.StoredContainer;
import io.floci.oci.services.containerinstances.model.StoredContainerInstance;
import io.floci.oci.services.identity.IdentityService;
import io.floci.oci.services.vault.VaultSecretsService;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * OCI Container Instances ({@code /20210415}, oci-go-sdk/containerinstances). A create, delete,
 * start, stop and restart is a work request that finishes when Docker has done the work (the
 * Terraform provider polls it until {@code timeFinished} and reads the CREATED resource from it);
 * update and change-compartment finish at once. In mock mode nothing touches Docker and every
 * instance is ACTIVE as soon as it is created.
 *
 * <p>Every change to stored state happens under this object's lock. The Docker work runs on one
 * worker thread outside it, inside a request context for the instance's tenancy and region, and
 * records its outcome under the lock again, so a delete that arrives while a start is running
 * wins.
 */
@ApplicationScoped
public class ContainerInstancesService implements Resettable, CompartmentUsage {

    private static final Logger LOG = Logger.getLogger(ContainerInstancesService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String WR_SERVICE = "containerinstances";
    static final String API_PREFIX = "/20210415";
    static final String ENTITY_TYPE = "containerInstance";
    static final String INTERRUPTED = "The operation was interrupted by a restart of floci-oci";
    private static final long POLL_SECONDS = 5;
    private static final Set<String> FAULT_DOMAINS = Set.of("FAULT-DOMAIN-1", "FAULT-DOMAIN-2", "FAULT-DOMAIN-3");
    private static final DateTimeFormatter NAME_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /** Runs background work inside a request context for one tenancy and region. */
    @FunctionalInterface
    interface PartitionRunner {
        void runAs(String tenancyId, String region, Runnable task);
    }

    /** What CreateContainerInstance returns: the body and the work request to poll. */
    public record Created(StoredContainerInstance instance, String workRequestId) {
    }

    private final StorageBackend<String, StoredContainerInstance> instances;
    private final StorageBackend<String, StoredContainer> containers;
    private final EmulatorConfig config;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;
    private final ContainerInstanceManager manager;
    private final OciContext ociContext;
    private final NetworkReferences networkReferences;
    private final UnaryOperator<String> pullableImage;
    private final Predicate<String> knownAvailabilityDomain;
    private final Instance<VaultSecretsService> vaultSecrets;
    private final PartitionRunner partitions;
    private final Clock clock;
    private final Executor lifecycle;
    private final ExecutorService ownedLifecycle;
    private final ScheduledExecutorService poller;

    @Inject
    public ContainerInstancesService(StorageFactory storageFactory, EmulatorConfig config,
                                     ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                                     ContainerInstanceManager manager, OciContext ociContext,
                                     Instance<NetworkReferences> networkReferences,
                                     Instance<ContainerImageResolver> imageResolvers,
                                     IdentityService identity, Instance<VaultSecretsService> vaultSecrets,
                                     RequestContextPropagation requestContexts) {
        this(storageFactory.create("containerinstances", "containerinstances-instances.json",
                        new TypeReference<Map<String, StoredContainerInstance>>() {}),
                storageFactory.create("containerinstances", "containerinstances-containers.json",
                        new TypeReference<Map<String, StoredContainer>>() {}),
                config, serviceRegistry, workRequests, manager, ociContext,
                NetworkReferences.of(networkReferences),
                image -> imageResolvers.isResolvable() ? imageResolvers.get().pullable(image) : image,
                ad -> !config.services().identity().enabled() || identity.isAvailabilityDomain(ad),
                vaultSecrets, requestContexts::runAs, Clock.systemUTC(), null);
    }

    /**
     * Storage-injecting constructor for tests.
     *
     * @param lifecycle runs the Docker work; null for the service's own worker thread
     */
    ContainerInstancesService(StorageBackend<String, StoredContainerInstance> instances,
                              StorageBackend<String, StoredContainer> containers, EmulatorConfig config,
                              ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                              ContainerInstanceManager manager, OciContext ociContext,
                              NetworkReferences networkReferences, UnaryOperator<String> pullableImage,
                              Predicate<String> knownAvailabilityDomain, Instance<VaultSecretsService> vaultSecrets,
                              PartitionRunner partitions, Clock clock, Executor lifecycle) {
        this.instances = instances;
        this.containers = containers;
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.manager = manager;
        this.ociContext = ociContext;
        this.networkReferences = networkReferences;
        this.pullableImage = pullableImage;
        this.knownAvailabilityDomain = knownAvailabilityDomain;
        this.vaultSecrets = vaultSecrets;
        this.partitions = partitions;
        this.clock = clock;
        // No thread starts until the first task is submitted, which only real mode does.
        this.ownedLifecycle = lifecycle != null ? null : Executors.newSingleThreadExecutor(daemon("ci-lifecycle"));
        this.lifecycle = lifecycle != null ? lifecycle : ownedLifecycle;
        this.poller = Executors.newSingleThreadScheduledExecutor(daemon("ci-poller"));
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("containerinstances")
                .enabled(config.services().containerinstances().enabled())
                .storageKey("containerinstances")
                .resourceClasses(ContainerInstancesController.class)
                .build());
        if (realMode() && config.services().containerinstances().enabled()) {
            poller.scheduleWithFixedDelay(this::pollSafely, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
            restartPersisted();
        }
    }

    @PreDestroy
    void shutdown() {
        poller.shutdownNow();
        if (ownedLifecycle != null) {
            ownedLifecycle.shutdownNow();
        }
        if (realMode()) {
            for (StoredContainerInstance instance : TenancyAwareStorageBackend.scanAll(instances)) {
                if (Set.of("ACTIVE", "UPDATING", "CREATING").contains(instance.getLifecycleState())) {
                    try {
                        manager.stopPod(instance, containersOf(instance));
                    } catch (RuntimeException e) {
                        LOG.warnv("Could not stop container instance {0}: {1}", instance.getId(), e.getMessage());
                    }
                }
            }
        }
    }

    /** Removes every pod and volume, then forgets every container instance and its VNICs. */
    @Override
    public synchronized void clear() {
        for (StoredContainerInstance instance : TenancyAwareStorageBackend.scanAll(instances)) {
            if (realMode()) {
                try {
                    manager.removePod(instance, containersOf(instance));
                } catch (RuntimeException e) {
                    LOG.warnv("Could not remove container instance {0}: {1}", instance.getId(), e.getMessage());
                }
            }
        }
        instances.clear();
        containers.clear();
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return CompartmentUsage.live(instances, tenancyId, compartmentId, StoredContainerInstance::getCompartmentId,
                StoredContainerInstance::getLifecycleState, i -> "container instance " + i.getId()).toList();
    }

    boolean realMode() {
        return manager != null && !config.services().containerinstances().mock();
    }

    // ── Create ─────────────────────────────────────────────────────────────────

    public synchronized Created create(Map<String, Object> details) {
        Map<String, Object> d = details != null ? details : Map.of();
        String compartmentId = ContainerInstancesRequests.requireString(d, "compartmentId", "compartmentId");
        String availabilityDomain = ContainerInstancesRequests.requireString(d, "availabilityDomain",
                "availabilityDomain");
        if (!knownAvailabilityDomain.test(availabilityDomain)) {
            throw OciException.invalidParameter("Unknown availability domain: " + availabilityDomain);
        }
        String faultDomain = ContainerInstancesRequests.string(d, "faultDomain");
        if (faultDomain != null && !FAULT_DOMAINS.contains(faultDomain)) {
            throw OciException.invalidParameter("Invalid faultDomain: " + faultDomain);
        }
        String shapeName = ContainerInstancesRequests.requireString(d, "shape", "shape");
        ContainerInstancesCatalog.Shape shape = ContainerInstancesCatalog.shape(shapeName, ociContext.region())
                .orElseThrow(() -> OciException.invalidParameter("Invalid shape: " + shapeName));
        Map<String, Object> shapeConfigRequest = ContainerInstancesRequests.map(d, "shapeConfig");
        if (shapeConfigRequest == null) {
            throw OciException.missingParameter("Missing required parameter: shapeConfig");
        }
        Map<String, Object> shapeConfig = ContainerInstancesCatalog.shapeConfig(shape, shapeConfigRequest);
        String restartPolicy = ContainerInstancesRequests.string(d, "containerRestartPolicy");
        ContainerInstancesRequests.requireEnum(restartPolicy, ContainerInstancesRequests.RESTART_POLICIES,
                "containerRestartPolicy");
        Object grace = d.get("gracefulShutdownTimeoutInSeconds");
        if (grace != null && !(grace instanceof Number)) {
            throw OciException.invalidParameter("gracefulShutdownTimeoutInSeconds must be a number: " + grace);
        }
        List<Map<String, Object>> volumes = ContainerInstancesRequests.volumes(
                ContainerInstancesRequests.objects(d, "volumes"));
        Set<String> volumeNames = new LinkedHashSet<>();
        volumes.forEach(volume -> volumeNames.add(String.valueOf(volume.get("name"))));
        List<Map<String, Object>> containerDetails = ContainerInstancesRequests.objects(d, "containers");
        if (containerDetails.isEmpty()) {
            throw OciException.missingParameter("Missing required parameter: containers");
        }
        List<ContainerRequest> containerRequests = new ArrayList<>();
        for (Map<String, Object> container : containerDetails) {
            containerRequests.add(ContainerInstancesRequests.container(container, volumeNames));
        }
        List<Map<String, Object>> vnicDetails = ContainerInstancesRequests.objects(d, "vnics");
        if (vnicDetails.isEmpty()) {
            throw OciException.missingParameter("Missing required parameter: vnics");
        }
        if (vnicDetails.size() > 1) {
            throw OciException.invalidParameter("A container instance has exactly one VNIC, got " + vnicDetails.size());
        }
        Map<String, Object> vnic = vnicDetails.get(0);
        String subnetId = ContainerInstancesRequests.requireString(vnic, "subnetId", "vnics.subnetId");
        networkReferences.requireSubnet(subnetId);
        List<String> nsgIds = ContainerInstancesRequests.strings(vnic, "nsgIds");
        networkReferences.requireNsgs(nsgIds);
        List<StoredContainerInstance.PullCredential> credentials = new ArrayList<>();
        List<Map<String, Object>> pullSecrets = imagePullSecrets(ContainerInstancesRequests.objects(d, "imagePullSecrets"),
                credentials);
        Map<String, Object> securityContext = ContainerInstancesRequests.securityContext(
                ContainerInstancesRequests.map(d, "securityContext"));
        Map<String, Object> dnsConfig = ContainerInstancesRequests.map(d, "dnsConfig");
        Map<String, String> freeformTags = ContainerInstancesRequests.stringMap(d, "freeformTags");
        Map<String, Map<String, Object>> definedTags = ContainerInstancesRequests.definedTags(d);

        Instant now = clock.instant();
        StoredContainerInstance instance = new StoredContainerInstance();
        instance.setId(Ocids.generate("computecontainerinstance", ociContext.realm(), ociContext.regionCode()));
        instance.setTenancyId(ociContext.tenancyId());
        instance.setRegion(ociContext.region());
        instance.setCompartmentId(compartmentId);
        String displayName = ContainerInstancesRequests.string(d, "displayName");
        instance.setDisplayName(displayName != null ? displayName : "containerinstance" + NAME_STAMP.format(now));
        instance.setAvailabilityDomain(availabilityDomain);
        instance.setTimeCreated(now.toString());
        instance.setTimeUpdated(now.toString());
        instance.setEtag(Etags.newEtag());
        instance.setFreeformTags(freeformTags != null ? freeformTags : new LinkedHashMap<>());
        instance.setDefinedTags(definedTags != null ? definedTags : new LinkedHashMap<>());
        instance.setPullCredentials(credentials);
        Map<String, Object> a = instance.getAttributes();
        a.put("faultDomain", faultDomain != null ? faultDomain : "FAULT-DOMAIN-1");
        a.put("shape", shape.name());
        a.put("shapeConfig", shapeConfig);
        a.put("containerRestartPolicy", restartPolicy != null ? restartPolicy : "ALWAYS");
        a.put("gracefulShutdownTimeoutInSeconds", grace instanceof Number number ? number.longValue() : 0L);
        a.put("volumes", volumes);
        a.put("imagePullSecrets", pullSecrets);
        if (securityContext != null) {
            a.put("securityContext", securityContext);
        }
        if (dnsConfig != null) {
            a.put("dnsConfig", dnsConfig);
        }

        String requestedIp = ContainerInstancesRequests.string(vnic, "privateIp");
        instance.setVnicAddressFromWorkload(realMode() && requestedIp == null);
        Object publicIp = vnic.get("isPublicIpAssigned");
        Object skipCheck = vnic.get("skipSourceDestCheck");
        String vnicId = networkReferences.attachVnic(new NetworkReferences.VnicRequest(compartmentId,
                availabilityDomain, subnetId, ContainerInstancesRequests.string(vnic, "displayName"),
                ContainerInstancesRequests.string(vnic, "hostnameLabel"), nsgIds,
                skipCheck instanceof Boolean skip ? skip : null, requestedIp, !instance.isVnicAddressFromWorkload(),
                Boolean.TRUE.equals(publicIp), ContainerInstancesRequests.stringMap(vnic, "freeformTags"),
                ContainerInstancesRequests.definedTags(vnic)));
        instance.setVnicIds(new ArrayList<>(List.of(vnicId)));

        List<StoredContainer> created = new ArrayList<>();
        for (int i = 0; i < containerRequests.size(); i++) {
            ContainerRequest request = containerRequests.get(i);
            StoredContainer container = new StoredContainer();
            container.setId(Ocids.generate("computecontainer", ociContext.realm(), ociContext.regionCode()));
            container.setContainerInstanceId(instance.getId());
            container.setCompartmentId(compartmentId);
            container.setAvailabilityDomain(availabilityDomain);
            container.setDisplayName(request.displayName() != null ? request.displayName()
                    : instance.getDisplayName() + "-container-" + (i + 1));
            container.setImageUrl(request.imageUrl());
            container.setTimeCreated(now.toString());
            container.setTimeUpdated(now.toString());
            container.setEtag(Etags.newEtag());
            container.setFreeformTags(request.freeformTags() != null ? request.freeformTags() : new LinkedHashMap<>());
            container.setDefinedTags(request.definedTags() != null ? request.definedTags() : new LinkedHashMap<>());
            container.setAttributes(new LinkedHashMap<>(request.attributes()));
            container.getAttributes().put("faultDomain", a.get("faultDomain"));
            created.add(container);
            instance.getContainerIds().add(container.getId());
        }

        List<StoredWorkRequest.Resource> resources = List.of(resource("CREATED", instance.getId()));
        String workRequestId;
        if (realMode()) {
            instance.setLifecycleState("CREATING");
            created.forEach(container -> container.setLifecycleState("CREATING"));
            workRequestId = workRequests.inProgress(WR_SERVICE, "CREATE_CONTAINER_INSTANCE", compartmentId, resources);
            instance.setWorkRequestId(workRequestId);
        } else {
            instance.setLifecycleState("ACTIVE");
            created.forEach(container -> container.setLifecycleState("ACTIVE"));
            workRequestId = workRequests.succeeded(WR_SERVICE, "CREATE_CONTAINER_INSTANCE", compartmentId, resources);
        }
        created.forEach(container -> containers.put(container.getId(), container));
        instances.put(instance.getId(), instance);
        LOG.infov("createContainerInstance {0} ({1})", instance.getDisplayName(), instance.getId());
        if (realMode()) {
            launch(instance, workRequestId, false);
        }
        return new Created(instance, workRequestId);
    }

    /**
     * The read shape of {@code imagePullSecrets} (registryEndpoint, plus secretId for VAULT) and,
     * into {@code credentials}, the decoded username and password each pull uses. BASIC carries
     * them base64-encoded (create_basic_image_pull_secret_details.go); VAULT names a secret whose
     * content is {@code {"username": ..., "password": ...}} (create_vault_image_pull_secret_details.go),
     * read from this emulator's Vault.
     */
    private List<Map<String, Object>> imagePullSecrets(List<Map<String, Object>> requested,
                                                       List<StoredContainerInstance.PullCredential> credentials) {
        List<Map<String, Object>> read = new ArrayList<>();
        for (Map<String, Object> secret : requested) {
            String type = ContainerInstancesRequests.requireString(secret, "secretType", "imagePullSecrets.secretType");
            String endpoint = ContainerInstancesRequests.requireString(secret, "registryEndpoint",
                    "imagePullSecrets.registryEndpoint");
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("secretType", type);
            wire.put("registryEndpoint", endpoint);
            switch (type) {
                case "BASIC" -> credentials.add(new StoredContainerInstance.PullCredential(endpoint,
                        decode(ContainerInstancesRequests.requireString(secret, "username", "imagePullSecrets.username"),
                                "imagePullSecrets.username"),
                        decode(ContainerInstancesRequests.requireString(secret, "password", "imagePullSecrets.password"),
                                "imagePullSecrets.password")));
                case "VAULT" -> {
                    String secretId = ContainerInstancesRequests.requireString(secret, "secretId",
                            "imagePullSecrets.secretId");
                    wire.put("secretId", secretId);
                    credentials.add(vaultCredential(endpoint, secretId));
                }
                default -> throw OciException.invalidParameter("Invalid imagePullSecrets.secretType: " + type);
            }
            read.add(wire);
        }
        return read;
    }

    private static String decode(String value, String field) {
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw OciException.invalidParameter(field + " must be base64");
        }
    }

    private StoredContainerInstance.PullCredential vaultCredential(String endpoint, String secretId) {
        OciException unknown = OciException.relatedResourceNotAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: secret " + secretId);
        if (vaultSecrets == null || !vaultSecrets.isResolvable() || !config.services().vault().enabled()) {
            throw unknown;
        }
        String content;
        try {
            content = vaultSecrets.get().getBundle(secretId, null, null).version().getContent();
        } catch (OciException e) {
            throw unknown;
        }
        try {
            Map<String, Object> json = MAPPER.readValue(decode(content, "secret content"),
                    new TypeReference<Map<String, Object>>() {});
            if (!(json.get("username") instanceof String username) || !(json.get("password") instanceof String password)) {
                throw OciException.invalidParameter("Secret " + secretId + " must hold {\"username\": ..., \"password\": ...}");
            }
            return new StoredContainerInstance.PullCredential(endpoint, username, password);
        } catch (JsonProcessingException e) {
            throw OciException.invalidParameter("Secret " + secretId + " must hold {\"username\": ..., \"password\": ...}");
        }
    }

    // ── Read ───────────────────────────────────────────────────────────────────

    public StoredContainerInstance get(String id) {
        return instances.get(id).orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: container instance " + id));
    }

    public List<StoredContainerInstance> list(String compartmentId, String lifecycleState, String displayName,
                                              String availabilityDomain, String sortBy, String sortOrder) {
        if (compartmentId == null) {
            throw OciException.missingParameter("Missing required parameter: compartmentId");
        }
        return instances.scan(k -> true).stream()
                .filter(i -> compartmentId.equals(i.getCompartmentId()))
                .filter(i -> lifecycleState == null || lifecycleState.equals(i.getLifecycleState()))
                .filter(i -> displayName == null || displayName.equals(i.getDisplayName()))
                .filter(i -> availabilityDomain == null || availabilityDomain.equalsIgnoreCase(i.getAvailabilityDomain()))
                .sorted(order(sortBy, sortOrder, StoredContainerInstance::getDisplayName,
                        StoredContainerInstance::getTimeCreated))
                .toList();
    }

    public StoredContainer getContainer(String id) {
        return containers.get(id).orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: container " + id));
    }

    public List<StoredContainer> listContainers(String compartmentId, String containerInstanceId, String lifecycleState,
                                                String displayName, String availabilityDomain, String sortBy,
                                                String sortOrder) {
        if (compartmentId == null) {
            throw OciException.missingParameter("Missing required parameter: compartmentId");
        }
        return containers.scan(k -> true).stream()
                .filter(c -> compartmentId.equals(c.getCompartmentId()))
                .filter(c -> containerInstanceId == null || containerInstanceId.equals(c.getContainerInstanceId()))
                .filter(c -> lifecycleState == null || lifecycleState.equals(c.getLifecycleState()))
                .filter(c -> displayName == null || displayName.equals(c.getDisplayName()))
                .filter(c -> availabilityDomain == null || availabilityDomain.equalsIgnoreCase(c.getAvailabilityDomain()))
                .sorted(order(sortBy, sortOrder, StoredContainer::getDisplayName, StoredContainer::getTimeCreated))
                .toList();
    }

    /** timeCreated sorts descending and displayName ascending unless sortOrder says otherwise. */
    private static <T> Comparator<T> order(String sortBy, String sortOrder,
                                           Function<T, String> name, Function<T, String> created) {
        ContainerInstancesRequests.requireEnum(sortBy, Set.of("timeCreated", "displayName"), "sortBy");
        ContainerInstancesRequests.requireEnum(sortOrder, Set.of("ASC", "DESC"), "sortOrder");
        boolean byName = "displayName".equals(sortBy);
        Comparator<T> order = byName
                ? Comparator.comparing(t -> Objects.toString(name.apply(t), ""), String.CASE_INSENSITIVE_ORDER)
                : Comparator.comparing(created);
        boolean descending = sortOrder == null ? !byName : "DESC".equals(sortOrder);
        return descending ? order.reversed() : order;
    }

    public List<StoredContainer> containersOf(StoredContainerInstance instance) {
        List<StoredContainer> found = new ArrayList<>();
        for (String id : instance.getContainerIds()) {
            containers.get(id).ifPresent(found::add);
        }
        return found;
    }

    public List<ContainerInstancesCatalog.Shape> shapes(String compartmentId, String availabilityDomain) {
        if (compartmentId == null) {
            throw OciException.missingParameter("Missing required parameter: compartmentId");
        }
        return ContainerInstancesCatalog.shapesIn(ociContext.region());
    }

    /** The container's logs as text: Docker's in real mode, nothing in mock mode. */
    public String logs(String containerId) {
        StoredContainer container = getContainer(containerId);
        if (!realMode() || container.getDockerName() == null) {
            return "";
        }
        return manager.logs(container);
    }

    // ── Update, compartment ────────────────────────────────────────────────────

    /** UpdateContainerInstanceDetails: displayName and tags; a work request that finishes at once. */
    public synchronized String update(String id, Map<String, Object> details, String ifMatch) {
        StoredContainerInstance instance = mutable(id, ifMatch, "ACTIVE", "INACTIVE");
        String displayName = ContainerInstancesRequests.string(details, "displayName");
        Map<String, String> freeformTags = ContainerInstancesRequests.stringMap(details, "freeformTags");
        Map<String, Map<String, Object>> definedTags = ContainerInstancesRequests.definedTags(details);
        if (displayName != null) {
            instance.setDisplayName(displayName);
        }
        if (freeformTags != null) {
            instance.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            instance.setDefinedTags(definedTags);
        }
        touch(instance);
        return workRequests.succeeded(WR_SERVICE, "UPDATE_CONTAINER_INSTANCE", instance.getCompartmentId(),
                List.of(resource("UPDATED", id)));
    }

    /** UpdateContainerDetails: displayName and tags of one container. */
    public synchronized String updateContainer(String containerId, Map<String, Object> details, String ifMatch) {
        StoredContainer container = getContainer(containerId);
        Etags.checkIfMatch(ifMatch, container.getEtag());
        String displayName = ContainerInstancesRequests.string(details, "displayName");
        Map<String, String> freeformTags = ContainerInstancesRequests.stringMap(details, "freeformTags");
        Map<String, Map<String, Object>> definedTags = ContainerInstancesRequests.definedTags(details);
        if (displayName != null) {
            container.setDisplayName(displayName);
        }
        if (freeformTags != null) {
            container.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            container.setDefinedTags(definedTags);
        }
        container.setTimeUpdated(clock.instant().toString());
        container.setEtag(Etags.newEtag());
        containers.put(containerId, container);
        return workRequests.succeeded(WR_SERVICE, "UPDATE_CONTAINER", container.getCompartmentId(),
                List.of(WorkRequestService.resource("container", "UPDATED", containerId,
                        API_PREFIX + "/containers/" + containerId)));
    }

    /** ChangeContainerInstanceCompartment: the instance and its containers move together. */
    public synchronized String changeCompartment(String id, String compartmentId, String ifMatch) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: compartmentId");
        }
        StoredContainerInstance instance = mutable(id, ifMatch, "ACTIVE", "INACTIVE", "FAILED");
        instance.setCompartmentId(compartmentId);
        for (StoredContainer container : containersOf(instance)) {
            container.setCompartmentId(compartmentId);
            container.setEtag(Etags.newEtag());
            containers.put(container.getId(), container);
        }
        touch(instance);
        return workRequests.succeeded(WR_SERVICE, "MOVE_CONTAINER_INSTANCE", compartmentId,
                List.of(resource("UPDATED", id)));
    }

    // ── Lifecycle actions ──────────────────────────────────────────────────────

    public synchronized String delete(String id, String ifMatch) {
        StoredContainerInstance instance = get(id);
        Etags.checkIfMatch(ifMatch, instance.getEtag());
        if (Set.of("DELETING", "DELETED").contains(instance.getLifecycleState())) {
            throw incorrectState(instance);
        }
        List<StoredWorkRequest.Resource> resources = List.of(resource("DELETED", id));
        if (!realMode()) {
            markDeleted(instance);
            return workRequests.succeeded(WR_SERVICE, "DELETE_CONTAINER_INSTANCE", instance.getCompartmentId(),
                    resources);
        }
        String workRequestId = workRequests.inProgress(WR_SERVICE, "DELETE_CONTAINER_INSTANCE",
                instance.getCompartmentId(), resources);
        finishPending(instance, INTERRUPTED);
        instance.setLifecycleState("DELETING");
        instance.setWorkRequestId(workRequestId);
        setContainerStates(instance, "DELETING");
        touch(instance);
        submit(instance, () -> runDelete(id, workRequestId));
        return workRequestId;
    }

    public synchronized String stop(String id, String ifMatch) {
        StoredContainerInstance instance = mutable(id, ifMatch, "ACTIVE");
        List<StoredWorkRequest.Resource> resources = List.of(resource("UPDATED", id));
        if (!realMode()) {
            instance.setLifecycleState("INACTIVE");
            setContainerStates(instance, "INACTIVE");
            touch(instance);
            return workRequests.succeeded(WR_SERVICE, "STOP_CONTAINER_INSTANCE", instance.getCompartmentId(), resources);
        }
        String workRequestId = workRequests.inProgress(WR_SERVICE, "STOP_CONTAINER_INSTANCE",
                instance.getCompartmentId(), resources);
        instance.setLifecycleState("UPDATING");
        instance.setWorkRequestId(workRequestId);
        touch(instance);
        submit(instance, () -> {
            StoredContainerInstance current = snapshot(id);
            RuntimeException failure = null;
            if (current != null) {
                try {
                    manager.stopPod(current, containersOf(current));
                } catch (RuntimeException e) {
                    failure = e;
                }
            }
            settle(id, workRequestId, failure, "INACTIVE", "INACTIVE", null);
        });
        return workRequestId;
    }

    public synchronized String start(String id, String ifMatch) {
        StoredContainerInstance instance = mutable(id, ifMatch, "INACTIVE");
        List<StoredWorkRequest.Resource> resources = List.of(resource("UPDATED", id));
        if (!realMode()) {
            instance.setLifecycleState("ACTIVE");
            setContainerStates(instance, "ACTIVE");
            touch(instance);
            return workRequests.succeeded(WR_SERVICE, "START_CONTAINER_INSTANCE", instance.getCompartmentId(), resources);
        }
        String workRequestId = workRequests.inProgress(WR_SERVICE, "START_CONTAINER_INSTANCE",
                instance.getCompartmentId(), resources);
        instance.setLifecycleState("UPDATING");
        instance.setWorkRequestId(workRequestId);
        touch(instance);
        launch(instance, workRequestId, true);
        return workRequestId;
    }

    public synchronized String restart(String id, String ifMatch) {
        StoredContainerInstance instance = mutable(id, ifMatch, "ACTIVE");
        List<StoredWorkRequest.Resource> resources = List.of(resource("UPDATED", id));
        if (!realMode()) {
            touch(instance);
            return workRequests.succeeded(WR_SERVICE, "RESTART_CONTAINER_INSTANCE", instance.getCompartmentId(),
                    resources);
        }
        String workRequestId = workRequests.inProgress(WR_SERVICE, "RESTART_CONTAINER_INSTANCE",
                instance.getCompartmentId(), resources);
        instance.setLifecycleState("UPDATING");
        instance.setWorkRequestId(workRequestId);
        touch(instance);
        submit(instance, () -> {
            StoredContainerInstance current = snapshot(id);
            RuntimeException failure = null;
            if (current != null) {
                try {
                    for (StoredContainer container : containersOf(current)) {
                        manager.restartContainer(container);
                    }
                } catch (RuntimeException e) {
                    failure = e;
                }
            }
            settle(id, workRequestId, failure, "ACTIVE", "ACTIVE", null);
        });
        return workRequestId;
    }

    // ── Background work ────────────────────────────────────────────────────────

    /** Starts the pod (create, or start of a stopped instance) and records the outcome. */
    private void launch(StoredContainerInstance instance, String workRequestId, boolean restart) {
        String id = instance.getId();
        submit(instance, () -> {
            StoredContainerInstance current = snapshot(id);
            if (current == null) {
                return;
            }
            String ip = null;
            RuntimeException failure = null;
            try {
                List<StoredContainer> pod = containersOf(current);
                ip = restart ? manager.restartPod(current, pod, pullableImage)
                        : manager.startPod(current, pod, pullableImage);
                synchronized (this) {
                    for (StoredContainer container : pod) {
                        containers.get(container.getId()).ifPresent(stored -> {
                            stored.setDockerName(container.getDockerName() != null
                                    ? container.getDockerName() : manager.containerName(stored));
                            containers.put(stored.getId(), stored);
                        });
                    }
                }
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not start container instance {0}", id);
                failure = e;
            }
            settle(id, workRequestId, failure, "ACTIVE", "FAILED", ip);
        });
    }

    /**
     * Records the end of a lifecycle step under the lock: the instance and its containers take
     * {@code success} (or {@code failure} with the error), the VNIC learns the pod's address, and
     * the work request finishes. A step whose work request is no longer the instance's (a delete
     * came in) leaves the instance alone, apart from removing what it started.
     */
    private synchronized void settle(String id, String workRequestId, RuntimeException error, String success,
                                     String failure, String podIp) {
        StoredContainerInstance instance = instances.get(id).orElse(null);
        if (instance == null || !workRequestId.equals(instance.getWorkRequestId())) {
            if (instance == null && realMode()) {
                LOG.infov("Container instance {0} went away while it was starting", id);
            }
            return;
        }
        instance.setWorkRequestId(null);
        if (error == null) {
            instance.setLifecycleState(success);
            instance.setLifecycleDetails(null);
            setContainerStates(instance, success);
            if (podIp != null && instance.isVnicAddressFromWorkload()) {
                instance.getVnicIds().forEach(vnicId -> networkReferences.setVnicPrivateIp(vnicId, podIp));
            }
        } else {
            instance.setLifecycleState(failure);
            instance.setLifecycleDetails(error.getMessage());
            setContainerStates(instance, failure);
        }
        touch(instance);
        workRequests.finish(instance.getTenancyId(), instance.getRegion(), workRequestId,
                error == null ? "SUCCEEDED" : "FAILED",
                error == null ? List.of() : List.of(new StoredWorkRequest.ErrorEntry("InternalServerError",
                        Objects.toString(error.getMessage(), error.getClass().getSimpleName()))));
    }

    private void runDelete(String id, String workRequestId) {
        StoredContainerInstance current = snapshot(id);
        if (current == null) {
            return;
        }
        try {
            manager.removePod(current, containersOf(current));
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove the containers of container instance {0}: {1}", id, e.getMessage());
        }
        synchronized (this) {
            StoredContainerInstance instance = instances.get(id).orElse(null);
            if (instance == null || !workRequestId.equals(instance.getWorkRequestId())) {
                return;
            }
            instance.setWorkRequestId(null);
            markDeleted(instance);
            workRequests.finish(instance.getTenancyId(), instance.getRegion(), workRequestId, "SUCCEEDED");
        }
    }

    /** DELETED, its containers DELETED, its VNICs detached. The record stays, as OCI keeps it for a while. */
    private void markDeleted(StoredContainerInstance instance) {
        instance.setLifecycleState("DELETED");
        setContainerStates(instance, "DELETED");
        for (String vnicId : instance.getVnicIds()) {
            networkReferences.detachVnic(vnicId);
        }
        touch(instance);
    }

    /** A work request the instance still has open is closed as FAILED before another takes over. */
    private void finishPending(StoredContainerInstance instance, String reason) {
        if (instance.getWorkRequestId() != null) {
            workRequests.finish(instance.getTenancyId(), instance.getRegion(), instance.getWorkRequestId(), "FAILED",
                    List.of(new StoredWorkRequest.ErrorEntry("InternalServerError", reason)));
            instance.setWorkRequestId(null);
        }
    }

    private void submit(StoredContainerInstance instance, Runnable task) {
        String tenancyId = instance.getTenancyId();
        String region = instance.getRegion();
        lifecycle.execute(() -> {
            try {
                partitions.runAs(tenancyId, region, task);
            } catch (RuntimeException e) {
                LOG.warnv(e, "Container instance {0}: background work failed", instance.getId());
            }
        });
    }

    private synchronized StoredContainerInstance snapshot(String id) {
        return instances.get(id).orElse(null);
    }

    private void pollSafely() {
        try {
            Set<List<String>> partitionsWithWork = new LinkedHashSet<>();
            for (StoredContainerInstance instance : TenancyAwareStorageBackend.scanAll(instances)) {
                if ("ACTIVE".equals(instance.getLifecycleState()) && instance.getTenancyId() != null) {
                    partitionsWithWork.add(List.of(instance.getTenancyId(), instance.getRegion()));
                }
            }
            for (List<String> partition : partitionsWithWork) {
                partitions.runAs(partition.get(0), partition.get(1), this::refreshActive);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Container instance poll failed: {0}", e.getMessage());
        }
    }

    /**
     * Reads what Docker reports for the containers of every ACTIVE instance in the current
     * partition: a container that exited is restarted when the restart policy says so (ALWAYS, or
     * ON_FAILURE with a non-zero exit code), otherwise it becomes INACTIVE with its exit code; an
     * instance whose containers have all stopped becomes INACTIVE.
     */
    void refreshActive() {
        List<StoredContainerInstance> active = instances.scan(k -> true).stream()
                .filter(i -> "ACTIVE".equals(i.getLifecycleState()) && i.getWorkRequestId() == null)
                .toList();
        for (StoredContainerInstance instance : active) {
            String policy = String.valueOf(instance.getAttributes().get("containerRestartPolicy"));
            for (StoredContainer container : containersOf(instance)) {
                if (!"ACTIVE".equals(container.getLifecycleState())) {
                    continue;
                }
                Optional<ContainerInstanceManager.ContainerState> state = manager.state(container);
                if (state.isEmpty() || state.get().running()) {
                    continue;
                }
                Integer exitCode = state.get().exitCode();
                boolean restart = "ALWAYS".equals(policy) || ("ON_FAILURE".equals(policy) && !Integer.valueOf(0).equals(exitCode));
                if (restart) {
                    try {
                        manager.restartContainer(container);
                    } catch (RuntimeException e) {
                        LOG.debugv("Could not restart container {0}: {1}", container.getId(), e.getMessage());
                    }
                }
                synchronized (this) {
                    containers.get(container.getId()).ifPresent(stored -> {
                        stored.setExitCode(exitCode);
                        stored.setTimeTerminated(state.get().finishedAt() != null
                                ? state.get().finishedAt().toString() : clock.instant().toString());
                        if (restart) {
                            stored.setContainerRestartAttemptCount(stored.getContainerRestartAttemptCount() + 1);
                        } else {
                            stored.setLifecycleState("INACTIVE");
                        }
                        stored.setTimeUpdated(clock.instant().toString());
                        containers.put(stored.getId(), stored);
                    });
                }
            }
            synchronized (this) {
                StoredContainerInstance current = instances.get(instance.getId()).orElse(null);
                if (current != null && "ACTIVE".equals(current.getLifecycleState()) && current.getWorkRequestId() == null
                        && containersOf(current).stream().allMatch(c -> "INACTIVE".equals(c.getLifecycleState()))) {
                    current.setLifecycleState("INACTIVE");
                    touch(current);
                }
            }
        }
    }

    /**
     * After a restart of floci-oci: a create that was running failed, a delete finishes, and an
     * instance that was ACTIVE (or being started, stopped or restarted) runs again.
     */
    private void restartPersisted() {
        for (StoredContainerInstance instance : TenancyAwareStorageBackend.scanAll(instances)) {
            if (instance.getTenancyId() == null) {
                continue;
            }
            partitions.runAs(instance.getTenancyId(), instance.getRegion(), () -> resume(instance.getId()));
        }
    }

    private synchronized void resume(String id) {
        StoredContainerInstance instance = instances.get(id).orElse(null);
        if (instance == null) {
            return;
        }
        switch (instance.getLifecycleState()) {
            case "CREATING" -> {
                String workRequestId = instance.getWorkRequestId();
                instance.setWorkRequestId(null);
                instance.setLifecycleState("FAILED");
                instance.setLifecycleDetails(INTERRUPTED);
                setContainerStates(instance, "FAILED");
                touch(instance);
                if (workRequestId != null) {
                    workRequests.finish(instance.getTenancyId(), instance.getRegion(), workRequestId, "FAILED",
                            List.of(new StoredWorkRequest.ErrorEntry("InternalServerError", INTERRUPTED)));
                }
            }
            case "DELETING" -> {
                String workRequestId = instance.getWorkRequestId() != null ? instance.getWorkRequestId()
                        : workRequests.inProgress(WR_SERVICE, "DELETE_CONTAINER_INSTANCE", instance.getCompartmentId(),
                        List.of(resource("DELETED", id)));
                instance.setWorkRequestId(workRequestId);
                instances.put(id, instance);
                submit(instance, () -> runDelete(id, workRequestId));
            }
            case "ACTIVE", "UPDATING" -> {
                finishPending(instance, INTERRUPTED);
                String workRequestId = workRequests.inProgress(WR_SERVICE, "START_CONTAINER_INSTANCE",
                        instance.getCompartmentId(), List.of(resource("UPDATED", id)));
                instance.setLifecycleState("UPDATING");
                instance.setWorkRequestId(workRequestId);
                touch(instance);
                launch(instance, workRequestId, true);
            }
            default -> {
                // INACTIVE, FAILED and DELETED instances have nothing running
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private StoredContainerInstance mutable(String id, String ifMatch, String... states) {
        StoredContainerInstance instance = get(id);
        Etags.checkIfMatch(ifMatch, instance.getEtag());
        if (!Set.of(states).contains(instance.getLifecycleState())) {
            throw incorrectState(instance);
        }
        return instance;
    }

    private static OciException incorrectState(StoredContainerInstance instance) {
        return new OciException("IncorrectState", "Container instance " + instance.getId() + " is "
                + instance.getLifecycleState(), 409);
    }

    private void setContainerStates(StoredContainerInstance instance, String state) {
        String now = clock.instant().toString();
        for (StoredContainer container : containersOf(instance)) {
            container.setLifecycleState(state);
            container.setTimeUpdated(now);
            if ("ACTIVE".equals(state)) {
                container.setExitCode(null);
                container.setTimeTerminated(null);
            }
            containers.put(container.getId(), container);
        }
    }

    private void touch(StoredContainerInstance instance) {
        instance.setTimeUpdated(clock.instant().toString());
        instance.setEtag(Etags.newEtag());
        instances.put(instance.getId(), instance);
    }

    private static StoredWorkRequest.Resource resource(String actionType, String id) {
        return WorkRequestService.resource(ENTITY_TYPE, actionType, id, API_PREFIX + "/containerInstances/" + id);
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
