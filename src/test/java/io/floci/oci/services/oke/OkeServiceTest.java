package io.floci.oci.services.oke;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.oke.model.StoredNodePool;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class OkeServiceTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..oketestcompartment";
    private static final String VCN = "ocid1.vcn.oc1.iad.testvcn";

    private OkeService service;
    private InMemoryStorage<String, StoredOkeCluster> clusters;
    private InMemoryStorage<String, StoredNodePool> nodePools;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        WorkRequestService workRequests = mock(WorkRequestService.class);

        clusters = new InMemoryStorage<>();
        nodePools = new InMemoryStorage<>();

        service = new OkeService(clusters, nodePools, config, null, workRequests, null);
    }

    @Test
    void createAndGetCluster() {
        OkeService.CreateClusterResult res = service.createCluster(COMPARTMENT, "my-cluster", VCN, "v1.30.1", null, Map.of("env", "test"), null);
        assertNotNull(res.cluster());
        assertNotNull(clusters);
        List<StoredOkeCluster> list = service.listClusters(COMPARTMENT);
        assertEquals(1, list.size());
        StoredOkeCluster cluster = list.get(0);
        assertEquals("my-cluster", cluster.getName());
        assertEquals(COMPARTMENT, cluster.getCompartmentId());

        StoredOkeCluster fetched = service.getCluster(cluster.getId());
        assertEquals(cluster.getId(), fetched.getId());
    }

    @Test
    void createClusterWithDuplicateNameReturnsNewCluster() {
        OkeService.CreateClusterResult res1 = service.createCluster(COMPARTMENT, "same-name", VCN, "v1.30.1", null, null, null);
        OkeService.CreateClusterResult res2 = service.createCluster(COMPARTMENT, "same-name", VCN, "v1.30.1", null, null, null);

        assertNotNull(res1.cluster().getId());
        assertNotNull(res2.cluster().getId());
        assertTrue(!res1.cluster().getId().equals(res2.cluster().getId()));
    }

    @Test
    void createNodePoolWithDuplicateNameReturnsNewNodePool() {
        OkeService.CreateClusterResult cRes = service.createCluster(COMPARTMENT, "cluster-dup", VCN, "v1.30.1", null, null, null);
        String clusterId = cRes.cluster().getId();

        OkeService.CreateNodePoolResult np1 = service.createNodePool(COMPARTMENT, clusterId, "pool-dup", "v1.30.1", "VM.Standard.E4.Flex", 2, null, null);
        OkeService.CreateNodePoolResult np2 = service.createNodePool(COMPARTMENT, clusterId, "pool-dup", "v1.30.1", "VM.Standard.E4.Flex", 2, null, null);

        assertNotNull(np1.nodePool().getId());
        assertNotNull(np2.nodePool().getId());
        assertTrue(!np1.nodePool().getId().equals(np2.nodePool().getId()));
    }

    @Test
    void getClusterNotFoundThrowsOciException() {
        assertThrows(OciException.class, () -> service.getCluster("ocid1.cluster.oc1.iad.nonexistent"));
    }

    @Test
    void createNodePoolNonexistentClusterThrowsOciException() {
        assertThrows(OciException.class, () ->
            service.createNodePool(COMPARTMENT, "ocid1.cluster.oc1.iad.nonexistent", "pool-1", "v1.30.1", "VM.Standard.E4.Flex", 2, null, null)
        );
    }

    @Test
    void updateAndDeleteCluster() {
        service.createCluster(COMPARTMENT, "cluster-1", VCN, "v1.29.1", null, null, null);
        StoredOkeCluster cluster = service.listClusters(COMPARTMENT).get(0);

        service.updateCluster(cluster.getId(), "updated-name", "v1.30.1");
        StoredOkeCluster updated = service.getCluster(cluster.getId());
        assertEquals("updated-name", updated.getName());
        assertEquals("v1.30.1", updated.getKubernetesVersion());

        service.deleteCluster(cluster.getId());
        assertThrows(OciException.class, () -> service.getCluster(cluster.getId()));
    }

    @Test
    void createAndListNodePools() {
        service.createCluster(COMPARTMENT, "cluster-1", VCN, "v1.30.1", null, null, null);
        StoredOkeCluster cluster = service.listClusters(COMPARTMENT).get(0);

        service.createNodePool(COMPARTMENT, cluster.getId(), "pool-1", "v1.30.1", "VM.Standard.E4.Flex", 2, null, null);
        List<StoredNodePool> pools = service.listNodePools(COMPARTMENT, cluster.getId());
        assertEquals(1, pools.size());
        StoredNodePool pool = pools.get(0);
        assertEquals("pool-1", pool.getName());
        assertEquals(cluster.getId(), pool.getClusterId());
        assertEquals(2, pool.getQuantityPerSubnet());
    }

    @Test
    void clearPurgesStorage() {
        service.createCluster(COMPARTMENT, "cluster-1", VCN, "v1.30.1", null, null, null);
        service.clear();
        assertTrue(service.listClusters(COMPARTMENT).isEmpty());
    }
}
