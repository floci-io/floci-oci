package io.floci.oci.services.oke;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Business logic service for OCI Container Engine for Kubernetes (OKE) API emulation.
 */
@ApplicationScoped
public class OkeService implements Resettable {

    private final StorageBackend<String, StoredOkeCluster> clusters;
    private final StorageBackend<String, StoredNodePool> nodePools;
    private final EmulatorConfig config;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;
    private final OkeClusterManager clusterManager;

    @Inject
    public OkeService(StorageFactory storageFactory, EmulatorConfig config,
                      ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                      OkeClusterManager clusterManager) {
        this(
            storageFactory.create("oke", "oke-clusters.json",
                new TypeReference<Map<String, StoredOkeCluster>>() {}),
            storageFactory.create("oke", "oke-nodepools.json",
                new TypeReference<Map<String, StoredNodePool>>() {}),
            config,
            serviceRegistry,
            workRequests,
            clusterManager
        );
    }

    OkeService(StorageBackend<String, StoredOkeCluster> clusters,
               StorageBackend<String, StoredNodePool> nodePools,
               EmulatorConfig config,
               ServiceRegistry serviceRegistry,
               WorkRequestService workRequests,
               OkeClusterManager clusterManager) {
        this.clusters = clusters;
        this.nodePools = nodePools;
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.clusterManager = clusterManager;
    }

    void onStart(@Observes StartupEvent ev) {
        if (serviceRegistry != null) {
            serviceRegistry.register(ServiceDescriptor.builder("oke")
                    .enabled(config.services().oke().enabled())
                    .storageKey("oke")
                    .resourceClasses(OkeController.class)
                    .build());
        }
        reconstructClusterState();
    }

    void reconstructClusterState() {
        if (clusterManager == null || config == null || config.services().oke().mock()) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<StoredOkeCluster> existingClusters = (clusters instanceof TenancyAwareStorageBackend)
                ? ((TenancyAwareStorageBackend<StoredOkeCluster>) clusters).scanAllTenancies()
                : clusters.scan(k -> true);
        for (StoredOkeCluster cluster : existingClusters) {
            clusterManager.registerExistingCluster(cluster);
        }
    }

    @Override
    public void clear() {
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

        String clusterId = Ocids.generate("cluster", config.defaultRealm(), regionShort());
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

        if (clusterManager != null) {
            clusterManager.startCluster(cluster);
        } else {
            cluster.setEndpoints(Map.of(
                "kubernetes", "https://127.0.0.1:6443",
                "privateEndpoint", "10.0.0.10:6443"
            ));
            cluster.setLifecycleState("ACTIVE");
        }

        clusters.put(clusterId, cluster);

        StoredWorkRequest.Resource res = WorkRequestService.resource(
            "cluster", "CREATED", clusterId, "/20180222/clusters/" + clusterId
        );
        String workRequestId = workRequests.succeeded("oke", "CREATE_CLUSTER", compartmentId, List.of(res));
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
        return workRequests.succeeded("oke", "UPDATE_CLUSTER", cluster.getCompartmentId(), List.of(res));
    }

    public String deleteCluster(String clusterId) {
        StoredOkeCluster cluster = getCluster(clusterId);
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
        return workRequests.succeeded("oke", "DELETE_CLUSTER", cluster.getCompartmentId(), List.of(res));
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

        String nodePoolId = Ocids.generate("nodepool", config.defaultRealm(), regionShort());
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
        String workRequestId = workRequests.succeeded("oke", "CREATE_NODEPOOL", compartmentId, List.of(res));
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
        return workRequests.succeeded("oke", "UPDATE_NODEPOOL", pool.getCompartmentId(), List.of(res));
    }

    public String deleteNodePool(String nodePoolId) {
        StoredNodePool pool = getNodePool(nodePoolId);
        pool.setLifecycleState("DELETED");
        nodePools.delete(nodePoolId);

        StoredWorkRequest.Resource res = WorkRequestService.resource(
            "nodepool", "DELETED", nodePoolId, "/20180222/nodePools/" + nodePoolId
        );
        return workRequests.succeeded("oke", "DELETE_NODEPOOL", pool.getCompartmentId(), List.of(res));
    }

    String regionShort() {
        return Ocids.regionShort(config.defaultRegion());
    }
}
