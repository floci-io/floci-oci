package io.floci.oci.services.oke;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.Resettable;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.oke.model.StoredNodePool;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Business logic service for OCI Container Engine for Kubernetes (OKE) API emulation.
 */
@ApplicationScoped
public class OkeService implements Resettable {

    private static final Logger LOG = Logger.getLogger(OkeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int API_TOKEN_BYTES = 32;
    private static final long READINESS_POLL_SECONDS = 3;

    /** A real-mode cluster still CREATING, with the tenancy that owns it and when it started. */
    record PendingCluster(String tenancyId, Instant since) {}

    /** A stored cluster together with the tenancy whose partition it lives in. */
    private record OwnedCluster(String tenancyId, StoredOkeCluster cluster) {}

    private final StorageBackend<String, StoredOkeCluster> clusters;
    private final StorageBackend<String, StoredNodePool> nodePools;
    private final EmulatorConfig config;
    private final OciContext ociContext;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;
    private final OkeClusterManager clusterManager;
    private final Supplier<String> tenancyId;
    private final Clock clock;
    private final Map<String, PendingCluster> pendingClusters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService readinessPoller;

    @Inject
    public OkeService(StorageFactory storageFactory, EmulatorConfig config,
                      ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                      OkeClusterManager clusterManager, OciContext ociContext) {
        this(
            storageFactory.create("oke", "oke-clusters.json",
                new TypeReference<Map<String, StoredOkeCluster>>() {}),
            storageFactory.create("oke", "oke-nodepools.json",
                new TypeReference<Map<String, StoredNodePool>>() {}),
            config,
            serviceRegistry,
            workRequests,
            clusterManager,
            ociContext::tenancyId,
            Clock.systemUTC(),
            ociContext
        );
    }

    OkeService(StorageBackend<String, StoredOkeCluster> clusters,
               StorageBackend<String, StoredNodePool> nodePools,
               EmulatorConfig config,
               ServiceRegistry serviceRegistry,
               WorkRequestService workRequests,
               OkeClusterManager clusterManager) {
        this(clusters, nodePools, config, serviceRegistry, workRequests, clusterManager,
                () -> config != null ? config.defaultTenancyId() : null, Clock.systemUTC(),
                OciContext.fromConfig(config));
    }

    OkeService(StorageBackend<String, StoredOkeCluster> clusters,
               StorageBackend<String, StoredNodePool> nodePools,
               EmulatorConfig config,
               ServiceRegistry serviceRegistry,
               WorkRequestService workRequests,
               OkeClusterManager clusterManager,
               Supplier<String> tenancyId,
               Clock clock,
               OciContext ociContext) {
        this.ociContext = ociContext;
        this.clusters = clusters;
        this.nodePools = nodePools;
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.clusterManager = clusterManager;
        this.tenancyId = tenancyId;
        this.clock = clock;
        // No thread starts until the first task is scheduled, which only real mode does.
        this.readinessPoller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "oke-readiness");
            thread.setDaemon(true);
            return thread;
        });
    }

    void onStart(@Observes StartupEvent ev) {
        if (serviceRegistry != null) {
            serviceRegistry.register(ServiceDescriptor.builder("oke")
                    .enabled(config.services().oke().enabled())
                    .storageKey("oke")
                    .resourceClasses(OkeController.class, OkeTokenWebhookController.class)
                    .build());
        }
        reconstructClusterState();
        if (realMode() && config.services().oke().enabled()) {
            startReadinessPoller();
        }
    }

    @PreDestroy
    void stopReadinessPoller() {
        readinessPoller.shutdownNow();
    }

    void reconstructClusterState() {
        List<OwnedCluster> existingClusters = backfillInternalFields();
        if (!realMode()) {
            return;
        }
        for (OwnedCluster owned : existingClusters) {
            StoredOkeCluster cluster = owned.cluster();
            if ("FAILED".equals(cluster.getLifecycleState())) {
                continue;
            }
            clusterManager.registerExistingCluster(cluster);
            if (cluster.getCaCertificate() == null || clusterManager.prunesVolumesOnStop()) {
                // Shutdown removed the volume holding the k3s CA, so the new sidecar has a new one.
                cluster.setCaCertificate(null);
                cluster.setLifecycleState("CREATING");
                pendingClusters.put(cluster.getId(), new PendingCluster(owned.tenancyId(), clock.instant()));
            }
            putCluster(owned.tenancyId(), cluster);
        }
    }

    /**
     * Fills the internal fields that clusters stored by an older floci-oci lack (API token,
     * tenancy, region) and persists the ones it changed, so the k3s token file, the webhook scope
     * and {@code CreateKubeconfig} agree after a restart.
     */
    private List<OwnedCluster> backfillInternalFields() {
        List<OwnedCluster> existingClusters = new ArrayList<>();
        if (clusters instanceof TenancyAwareStorageBackend<StoredOkeCluster> tenancyAware) {
            for (String owner : tenancyAware.tenancies()) {
                for (StoredOkeCluster cluster : tenancyAware.scanForTenancy(owner, k -> true)) {
                    if (backfill(cluster, owner)) {
                        tenancyAware.putForTenancy(owner, cluster.getId(), cluster);
                    }
                    existingClusters.add(new OwnedCluster(owner, cluster));
                }
            }
        } else {
            String owner = tenancyId.get();
            for (StoredOkeCluster cluster : clusters.scan(k -> true)) {
                if (backfill(cluster, owner)) {
                    clusters.put(cluster.getId(), cluster);
                }
                existingClusters.add(new OwnedCluster(owner, cluster));
            }
        }
        return existingClusters;
    }

    private boolean backfill(StoredOkeCluster cluster, String owner) {
        boolean changed = false;
        if (cluster.getApiToken() == null) {
            cluster.setApiToken(newApiToken());
            changed = true;
        }
        if (cluster.getTenancyId() == null && owner != null) {
            cluster.setTenancyId(owner);
            changed = true;
        }
        if (cluster.getRegion() == null && config != null && config.defaultRegion() != null) {
            cluster.setRegion(config.defaultRegion());
            changed = true;
        }
        return changed;
    }

    private void startReadinessPoller() {
        readinessPoller.scheduleWithFixedDelay(this::pollReadinessSafely,
                READINESS_POLL_SECONDS, READINESS_POLL_SECONDS, TimeUnit.SECONDS);
    }

    private void pollReadinessSafely() {
        try {
            pollReadiness();
        } catch (RuntimeException e) {
            LOG.warnv(e, "OKE readiness poll failed");
        }
    }

    /**
     * One pass over the CREATING clusters: ACTIVE once the k3s API answers (capturing its CA),
     * FAILED when the sidecar has exited or the ready timeout has passed. Each outcome also
     * finishes the CLUSTER_CREATE work request, which is what Terraform and the CLI wait on.
     */
    void pollReadiness() {
        for (Map.Entry<String, PendingCluster> entry : pendingClusters.entrySet()) {
            String clusterId = entry.getKey();
            PendingCluster pending = entry.getValue();
            Optional<StoredOkeCluster> found = findCluster(pending.tenancyId(), clusterId);
            if (found.isEmpty() || !"CREATING".equals(found.get().getLifecycleState())) {
                LOG.debugv("OKE cluster {0} is no longer CREATING in tenancy {1}; stop polling it",
                        clusterId, pending.tenancyId());
                pendingClusters.remove(clusterId);
                continue;
            }
            try {
                pollOne(pending, found.get());
            } catch (RuntimeException e) {
                LOG.warnv(e, "OKE readiness poll failed for cluster {0}", clusterId);
            }
        }
    }

    private void pollOne(PendingCluster pending, StoredOkeCluster cluster) {
        Optional<String> caCertificate = clusterManager.probeReady(cluster);
        if (caCertificate.isPresent()) {
            cluster.setCaCertificate(caCertificate.get());
            finishCreate(pending.tenancyId(), cluster, "ACTIVE", "SUCCEEDED", null);
        } else if (!clusterManager.isClusterRunning(cluster)) {
            finishCreate(pending.tenancyId(), cluster, "FAILED", "FAILED",
                    "The k3s sidecar exited before its API server became ready");
        } else if (clock.instant().isAfter(
                pending.since().plusSeconds(config.services().oke().readyTimeoutSeconds()))) {
            finishCreate(pending.tenancyId(), cluster, "FAILED", "FAILED",
                    "The k3s API server was not ready after "
                            + config.services().oke().readyTimeoutSeconds() + " seconds");
        }
    }

    /**
     * Records the outcome only while the cluster is still pending, atomically with dropping it
     * from {@link #pendingClusters}: a delete or reset that removed it during the probe wins, so
     * the poller never writes back a cluster that is gone.
     */
    private void finishCreate(String owner, StoredOkeCluster cluster, String lifecycleState,
                              String workRequestStatus, String details) {
        AtomicBoolean recorded = new AtomicBoolean();
        pendingClusters.computeIfPresent(cluster.getId(), (id, pending) -> {
            cluster.setLifecycleState(lifecycleState);
            cluster.setLifecycleDetails(details);
            putCluster(owner, cluster);
            if (cluster.getCreateWorkRequestId() != null) {
                workRequests.finish(owner, cluster.getCreateWorkRequestId(), workRequestStatus);
            }
            recorded.set(true);
            return null;
        });
        if (!recorded.get()) {
            LOG.debugv("OKE cluster {0} left pending before it became {1}", cluster.getId(), lifecycleState);
            return;
        }
        LOG.infov("OKE cluster {0} is {1}", cluster.getId(), lifecycleState);
    }

    /** Looks a cluster up in an explicit tenancy, for callers outside a request scope. */
    Optional<StoredOkeCluster> findCluster(String owner, String clusterId) {
        if (clusters instanceof TenancyAwareStorageBackend<StoredOkeCluster> tenancyAware) {
            return tenancyAware.getForTenancy(owner, clusterId);
        }
        return clusters.get(clusterId);
    }

    private void putCluster(String owner, StoredOkeCluster cluster) {
        if (clusters instanceof TenancyAwareStorageBackend<StoredOkeCluster> tenancyAware) {
            tenancyAware.putForTenancy(owner, cluster.getId(), cluster);
        } else {
            clusters.put(cluster.getId(), cluster);
        }
    }

    private boolean realMode() {
        return clusterManager != null && config != null && !config.services().oke().mock();
    }

    static String newApiToken() {
        byte[] bytes = new byte[API_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public void clear() {
        pendingClusters.clear();
        clusters.clear();
        nodePools.clear();
        if (clusterManager != null) {
            clusterManager.clear();
        }
    }

    public record CreateClusterResult(String workRequestId, StoredOkeCluster cluster) {}
    public record CreateNodePoolResult(String workRequestId, StoredNodePool nodePool) {}

    // ── Clusters ─────────────────────────────────────────────────────────────

    public CreateClusterResult createCluster(String compartmentId, String name, String vcnId,
                                String kubernetesVersion, String kmsKeyId,
                                Map<String, String> freeformTags,
                                Map<String, Map<String, Object>> definedTags) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: compartmentId");
        }
        if (name == null || name.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: name");
        }
        if (vcnId == null || vcnId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: vcnId");
        }

        String clusterId = Ocids.generate("cluster", ociContext.realm(), ociContext.regionCode());
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId(clusterId);
        cluster.setName(name);
        cluster.setCompartmentId(compartmentId);
        cluster.setVcnId(vcnId);
        cluster.setKubernetesVersion(kubernetesVersion != null ? kubernetesVersion : "v1.30.1");
        cluster.setKmsKeyId(kmsKeyId);
        cluster.setLifecycleState("CREATING");
        cluster.setLifecycleDetails(null);
        cluster.setMetadata(new StoredOkeCluster.ClusterMetadata(Instant.now()));
        cluster.setFreeformTags(freeformTags);
        cluster.setDefinedTags(definedTags);
        cluster.setApiToken(newApiToken());
        cluster.setTenancyId(tenancyId.get());
        cluster.setRegion(config.defaultRegion());

        if (clusterManager != null) {
            clusterManager.startCluster(cluster);
        } else {
            cluster.setEndpoints(Map.of(
                "kubernetes", "https://127.0.0.1:6443",
                "privateEndpoint", "10.0.0.10:6443"
            ));
            cluster.setLifecycleState("ACTIVE");
        }

        StoredWorkRequest.Resource res = WorkRequestService.resource(
            "cluster", "CREATED", clusterId, "/20180222/clusters/" + clusterId
        );
        boolean creating = "CREATING".equals(cluster.getLifecycleState());
        String workRequestId = creating
                ? workRequests.inProgress("oke", "CLUSTER_CREATE", compartmentId, List.of(res))
                : workRequests.succeeded("oke", "CLUSTER_CREATE", compartmentId, List.of(res));
        if (creating) {
            cluster.setCreateWorkRequestId(workRequestId);
        }
        clusters.put(clusterId, cluster);
        if (creating) {
            // Only once stored: the poller drops a pending entry whose cluster it cannot find.
            pendingClusters.put(clusterId, new PendingCluster(cluster.getTenancyId(), clock.instant()));
        }
        return new CreateClusterResult(workRequestId, cluster);
    }

    public StoredOkeCluster getCluster(String clusterId) {
        return clusters.get(clusterId)
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound("Cluster not found: " + clusterId));
    }

    public List<StoredOkeCluster> listClusters(String compartmentId) {
        return clusters.scan(k -> true).stream()
                .filter(c -> compartmentId == null || compartmentId.equals(c.getCompartmentId()))
                .toList();
    }

    public String updateCluster(String clusterId, String name, String kubernetesVersion) {
        StoredOkeCluster cluster = getCluster(clusterId);
        if (name != null && !name.isBlank()) {
            cluster.setName(name);
        }
        if (kubernetesVersion != null && !kubernetesVersion.isBlank()) {
            cluster.setKubernetesVersion(kubernetesVersion);
        }
        clusters.put(clusterId, cluster);

        StoredWorkRequest.Resource res = WorkRequestService.resource(
            "cluster", "UPDATED", clusterId, "/20180222/clusters/" + clusterId
        );
        return workRequests.succeeded("oke", "CLUSTER_UPDATE", cluster.getCompartmentId(), List.of(res));
    }

    public String deleteCluster(String clusterId) {
        StoredOkeCluster cluster = getCluster(clusterId);
        if (pendingClusters.remove(clusterId) != null && cluster.getCreateWorkRequestId() != null) {
            workRequests.finish(cluster.getTenancyId(), cluster.getCreateWorkRequestId(), "CANCELED");
        }
        if (clusterManager != null) {
            clusterManager.stopCluster(cluster);
        }

        List<StoredNodePool> dependentPools = listNodePools(null, clusterId);
        for (StoredNodePool pool : dependentPools) {
            pool.setLifecycleState("DELETED");
            nodePools.delete(pool.getId());
        }

        cluster.setLifecycleState("DELETED");
        clusters.delete(clusterId);

        StoredWorkRequest.Resource res = WorkRequestService.resource(
            "cluster", "DELETED", clusterId, "/20180222/clusters/" + clusterId
        );
        return workRequests.succeeded("oke", "CLUSTER_DELETE", cluster.getCompartmentId(), List.of(res));
    }

    public CreateNodePoolResult createNodePool(String compartmentId, String clusterId, String name,
                                 String kubernetesVersion, String nodeShape,
                                 int quantityPerSubnet,
                                 Map<String, String> freeformTags,
                                 Map<String, Map<String, Object>> definedTags) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: compartmentId");
        }
        if (clusterId == null || clusterId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: clusterId");
        }
        getCluster(clusterId);
        if (name == null || name.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: name");
        }

        String nodePoolId = Ocids.generate("nodepool", ociContext.realm(), ociContext.regionCode());
        StoredNodePool pool = new StoredNodePool();
        pool.setId(nodePoolId);
        pool.setName(name);
        pool.setCompartmentId(compartmentId);
        pool.setClusterId(clusterId);
        pool.setKubernetesVersion(kubernetesVersion != null ? kubernetesVersion : "v1.30.1");
        pool.setNodeShape(nodeShape != null ? nodeShape : "VM.Standard.E4.Flex");
        pool.setQuantityPerSubnet(quantityPerSubnet > 0 ? quantityPerSubnet : 1);
        pool.setLifecycleState("ACTIVE");
        pool.setTimeCreated(Instant.now());
        pool.setFreeformTags(freeformTags);
        pool.setDefinedTags(definedTags);

        nodePools.put(nodePoolId, pool);

        StoredWorkRequest.Resource res = WorkRequestService.resource(
            "nodepool", "CREATED", nodePoolId, "/20180222/nodePools/" + nodePoolId
        );
        String workRequestId = workRequests.succeeded("oke", "NODEPOOL_CREATE", compartmentId, List.of(res));
        return new CreateNodePoolResult(workRequestId, pool);
    }

    public StoredNodePool getNodePool(String nodePoolId) {
        return nodePools.get(nodePoolId)
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound("NodePool not found: " + nodePoolId));
    }

    public List<StoredNodePool> listNodePools(String compartmentId, String clusterId) {
        return nodePools.scan(k -> true).stream()
                .filter(p -> compartmentId == null || compartmentId.equals(p.getCompartmentId()))
                .filter(p -> clusterId == null || clusterId.equals(p.getClusterId()))
                .toList();
    }

    public String updateNodePool(String nodePoolId, String name, String kubernetesVersion, Integer quantityPerSubnet) {
        StoredNodePool pool = getNodePool(nodePoolId);
        if (name != null && !name.isBlank()) {
            pool.setName(name);
        }
        if (kubernetesVersion != null && !kubernetesVersion.isBlank()) {
            pool.setKubernetesVersion(kubernetesVersion);
        }
        if (quantityPerSubnet != null && quantityPerSubnet > 0) {
            pool.setQuantityPerSubnet(quantityPerSubnet);
        }
        nodePools.put(nodePoolId, pool);

        StoredWorkRequest.Resource res = WorkRequestService.resource(
            "nodepool", "UPDATED", nodePoolId, "/20180222/nodePools/" + nodePoolId
        );
        return workRequests.succeeded("oke", "NODEPOOL_UPDATE", pool.getCompartmentId(), List.of(res));
    }

    public String deleteNodePool(String nodePoolId) {
        StoredNodePool pool = getNodePool(nodePoolId);
        pool.setLifecycleState("DELETED");
        nodePools.delete(nodePoolId);

        StoredWorkRequest.Resource res = WorkRequestService.resource(
            "nodepool", "DELETED", nodePoolId, "/20180222/nodePools/" + nodePoolId
        );
        return workRequests.succeeded("oke", "NODEPOOL_DELETE", pool.getCompartmentId(), List.of(res));
    }

}
