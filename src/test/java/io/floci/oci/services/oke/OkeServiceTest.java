package io.floci.oci.services.oke;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.oke.model.StoredNodePool;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void ocidUsesMappedRegionShortCode() {
        OkeService.CreateClusterResult res = service.createCluster(COMPARTMENT, "region-cluster", VCN, "v1.30.1", null, null, null);
        assertTrue(res.cluster().getId().startsWith("ocid1.cluster.oc1.iad."));
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
    void deleteClusterDeletesDependentNodePools() {
        OkeService.CreateClusterResult cRes = service.createCluster(COMPARTMENT, "cluster-cascade", VCN, "v1.30.1", null, null, null);
        String clusterId = cRes.cluster().getId();

        OkeService.CreateNodePoolResult npRes = service.createNodePool(COMPARTMENT, clusterId, "pool-cascade", "v1.30.1", "VM.Standard.E4.Flex", 2, null, null);
        String poolId = npRes.nodePool().getId();

        service.deleteCluster(clusterId);

        assertThrows(OciException.class, () -> service.getCluster(clusterId));
        assertThrows(OciException.class, () -> service.getNodePool(poolId));
        assertTrue(service.listNodePools(COMPARTMENT, clusterId).isEmpty());
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

    @Test
    void startupReconstructsActiveClusterState() {
        OkeClusterManager mockManager = mock(OkeClusterManager.class);
        EmulatorConfig mockConfig = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig mockServices = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServicesConfig.OkeServiceConfig mockOkeConfig = mock(EmulatorConfig.ServicesConfig.OkeServiceConfig.class);

        lenient().when(mockConfig.services()).thenReturn(mockServices);
        lenient().when(mockServices.oke()).thenReturn(mockOkeConfig);
        lenient().when(mockOkeConfig.mock()).thenReturn(false);

        OkeService persistentService = new OkeService(clusters, nodePools, mockConfig, null, mock(WorkRequestService.class), mockManager);

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.reconstruct");
        cluster.setHostPort(16443);
        clusters.put(cluster.getId(), cluster);

        persistentService.reconstructClusterState();

        org.mockito.Mockito.verify(mockManager).registerExistingCluster(cluster);
    }

    @Test
    void startupReconstructsActiveClusterStateAcrossTenancies() {
        OkeClusterManager mockManager = mock(OkeClusterManager.class);
        EmulatorConfig mockConfig = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig mockServices = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServicesConfig.OkeServiceConfig mockOkeConfig = mock(EmulatorConfig.ServicesConfig.OkeServiceConfig.class);

        lenient().when(mockConfig.services()).thenReturn(mockServices);
        lenient().when(mockServices.oke()).thenReturn(mockOkeConfig);
        lenient().when(mockOkeConfig.mock()).thenReturn(false);

        StorageBackend<String, StoredOkeCluster> rawBackend = new InMemoryStorage<>();
        TenancyAwareStorageBackend<StoredOkeCluster> taClusters = new TenancyAwareStorageBackend<>(rawBackend, null, "ocid1.tenancy.oc1..flocitesttenancy");

        OkeService persistentService = new OkeService(taClusters, nodePools, mockConfig, null, mock(WorkRequestService.class), mockManager);

        StoredOkeCluster otherTenancyCluster = new StoredOkeCluster();
        otherTenancyCluster.setId("ocid1.cluster.oc1.iad.othertenancy");
        otherTenancyCluster.setHostPort(16444);

        rawBackend.put("ocid1.tenancy.oc1..customtenancy/ocid1.cluster.oc1.iad.othertenancy", otherTenancyCluster);

        persistentService.reconstructClusterState();

        org.mockito.Mockito.verify(mockManager).registerExistingCluster(otherTenancyCluster);
    }

    @Test
    void storedOkeClusterPersistsHostPortToStorageAndExcludesFromWire() throws Exception {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.test1234");
        cluster.setName("test-cluster");
        cluster.setHostPort(16443);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(cluster);
        assertTrue(json.contains("\"hostPort\":16443"), "Jackson storage serialization must include hostPort");

        StoredOkeCluster deserialized = mapper.readValue(json, StoredOkeCluster.class);
        assertEquals(16443, deserialized.getHostPort(), "Jackson deserialization must restore hostPort");

        Map<String, Object> wire = cluster.toWire();
        assertFalse(wire.containsKey("hostPort"));
    }

    @Test
    void createClusterAssignsDistinctApiTokensKeptOffTheWire() {
        StoredOkeCluster first = service.createCluster(COMPARTMENT, "first", VCN, null, null, null, null).cluster();
        StoredOkeCluster second = service.createCluster(COMPARTMENT, "second", VCN, null, null, null, null).cluster();

        assertNotNull(first.getApiToken());
        assertEquals(43, first.getApiToken().length(), "32 random bytes, base64url without padding");
        assertNotEquals(first.getApiToken(), second.getApiToken());
        assertFalse(first.getApiToken().contains(first.getId()), "token must not be derivable from the OCID");
        assertFalse(first.toWire().containsKey("apiToken"));
        assertFalse(first.toWire().containsValue(first.getApiToken()));
    }

    @Test
    void startupBackfillsApiTokenForClustersStoredBeforeTheField() {
        StoredOkeCluster legacy = new StoredOkeCluster();
        legacy.setId("ocid1.cluster.oc1.iad.legacy");
        clusters.put(legacy.getId(), legacy);

        service.reconstructClusterState();

        String token = clusters.get(legacy.getId()).orElseThrow().getApiToken();
        assertNotNull(token);
        service.reconstructClusterState();
        assertEquals(token, clusters.get(legacy.getId()).orElseThrow().getApiToken(), "an existing token is never rotated");
    }

    @Test
    void startupBackfillsApiTokenInTheClusterOwnTenancy() {
        StorageBackend<String, StoredOkeCluster> rawBackend = new InMemoryStorage<>();
        TenancyAwareStorageBackend<StoredOkeCluster> taClusters =
                new TenancyAwareStorageBackend<>(rawBackend, null, "ocid1.tenancy.oc1..flocitesttenancy");
        OkeService tenancyService = new OkeService(taClusters, nodePools, null, null, mock(WorkRequestService.class), null);

        StoredOkeCluster legacy = new StoredOkeCluster();
        legacy.setId("ocid1.cluster.oc1.iad.legacyother");
        String rawKey = "ocid1.tenancy.oc1..customtenancy/ocid1.cluster.oc1.iad.legacyother";
        rawBackend.put(rawKey, legacy);

        tenancyService.reconstructClusterState();

        assertNotNull(rawBackend.get(rawKey).orElseThrow().getApiToken());
        assertEquals(1, rawBackend.keys().size(), "the backfill must not copy the cluster into another tenancy");
    }

    @Test
    void findClusterSearchesEveryRegionOfTheTenancy() {
        EmulatorConfig customRegion = mock(EmulatorConfig.class);
        lenient().when(customRegion.defaultRegion()).thenReturn("iad-local");
        StorageBackend<String, StoredOkeCluster> rawBackend = new InMemoryStorage<>();
        TenancyAwareStorageBackend<StoredOkeCluster> taClusters =
                new TenancyAwareStorageBackend<>(rawBackend, () -> TENANCY + ":iad-local");
        OkeService regionalService = new OkeService(taClusters, nodePools, customRegion, null,
                mock(WorkRequestService.class), null);
        StoredOkeCluster ashburn = new StoredOkeCluster();
        ashburn.setId("ocid1.cluster.oc1.iad.ashburn");
        StoredOkeCluster local = new StoredOkeCluster();
        local.setId("ocid1.cluster.oc1.iad.local");
        rawBackend.put(TENANCY + ":us-ashburn-1/" + ashburn.getId(), ashburn);
        rawBackend.put(TENANCY + ":iad-local/" + local.getId(), local);

        assertTrue(regionalService.findCluster(TENANCY, ashburn.getId()).isPresent(),
                "a cluster created through us-ashburn-1 shares the iad code with iad-local");
        assertTrue(regionalService.findCluster(TENANCY, local.getId()).isPresent());
        assertTrue(regionalService.findCluster("ocid1.tenancy.oc1..other", local.getId()).isEmpty());
    }

    // -- Real-mode readiness: CREATING + IN_PROGRESS until the k3s API answers --

    private static final String TENANCY = "ocid1.tenancy.oc1..readinesstenancy";

    private OkeClusterManager readinessManager;
    private WorkRequestService realWorkRequests;
    private MutableClock clock;

    private OkeService realModeService() {
        EmulatorConfig realConfig = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(realConfig.defaultRealm()).thenReturn("oc1");
        lenient().when(realConfig.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(realConfig.services().oke().mock()).thenReturn(false);
        lenient().when(realConfig.services().oke().readyTimeoutSeconds()).thenReturn(300);
        readinessManager = mock(OkeClusterManager.class);
        realWorkRequests = new WorkRequestService(new InMemoryStorage<>(), realConfig);
        clock = new MutableClock(Instant.parse("2026-10-01T12:00:00Z"));
        return new OkeService(clusters, nodePools, realConfig, null, realWorkRequests, readinessManager,
                () -> TENANCY, clock, OciContext.fromConfig(realConfig));
    }

    @Test
    void realModeCreateStaysCreatingWithAnInProgressWorkRequestNamingTheCluster() {
        OkeService service = realModeService();

        OkeService.CreateClusterResult result = service.createCluster(COMPARTMENT, "pending", VCN, null, null, null, null);

        StoredOkeCluster cluster = result.cluster();
        assertEquals("CREATING", cluster.getLifecycleState());
        assertEquals(TENANCY, cluster.getTenancyId());
        assertEquals("us-ashburn-1", cluster.getRegion());
        assertEquals(result.workRequestId(), cluster.getCreateWorkRequestId());
        StoredWorkRequest workRequest = realWorkRequests.get(result.workRequestId());
        assertEquals("IN_PROGRESS", workRequest.getStatus());
        assertNull(workRequest.getTimeFinished(), "Terraform keeps polling only while timeFinished is unset");
        assertEquals(cluster.getId(), workRequest.getResources().get(0).getIdentifier(),
                "Terraform reads the cluster id from the first work-request read");
        assertFalse(cluster.toWire().containsKey("tenancyId"));
        assertFalse(cluster.toWire().containsKey("createWorkRequestId"));
    }

    @Test
    void pollReadinessActivatesTheClusterAndFinishesTheWorkRequest() {
        OkeService service = realModeService();
        OkeService.CreateClusterResult result = service.createCluster(COMPARTMENT, "ready", VCN, null, null, null, null);
        when(readinessManager.probeReady(any())).thenReturn(Optional.of("Q0FEQVRB"));

        service.pollReadiness();

        StoredOkeCluster cluster = service.getCluster(result.cluster().getId());
        assertEquals("ACTIVE", cluster.getLifecycleState());
        assertEquals("Q0FEQVRB", cluster.getCaCertificate());
        assertFalse(cluster.toWire().containsKey("caCertificate"));
        StoredWorkRequest workRequest = realWorkRequests.get(result.workRequestId());
        assertEquals("SUCCEEDED", workRequest.getStatus());
        assertEquals(100.0f, workRequest.getPercentComplete().floatValue());
        assertNotNull(workRequest.getTimeFinished());
    }

    @Test
    void pollReadinessKeepsWaitingWhileTheSidecarRunsWithinTheTimeout() {
        OkeService service = realModeService();
        OkeService.CreateClusterResult result = service.createCluster(COMPARTMENT, "booting", VCN, null, null, null, null);
        when(readinessManager.probeReady(any())).thenReturn(Optional.empty());
        when(readinessManager.isClusterRunning(any())).thenReturn(true);
        clock.advanceSeconds(299);

        service.pollReadiness();

        assertEquals("CREATING", service.getCluster(result.cluster().getId()).getLifecycleState());
        assertEquals("IN_PROGRESS", realWorkRequests.get(result.workRequestId()).getStatus());
    }

    @Test
    void pollReadinessDoesNotResurrectAClusterDeletedDuringTheProbe() {
        OkeService service = realModeService();
        OkeService.CreateClusterResult result = service.createCluster(COMPARTMENT, "doomed", VCN, null, null, null, null);
        String clusterId = result.cluster().getId();
        when(readinessManager.probeReady(any())).thenAnswer(invocation -> {
            service.deleteCluster(clusterId);
            return Optional.empty();
        });
        when(readinessManager.isClusterRunning(any())).thenReturn(false);

        service.pollReadiness();

        assertEquals(404, assertThrows(OciException.class, () -> service.getCluster(clusterId)).getHttpStatus());
        assertEquals("CANCELED", realWorkRequests.get(result.workRequestId()).getStatus());
    }

    @Test
    void pollReadinessFailsTheClusterWhenTheSidecarExits() {
        OkeService service = realModeService();
        OkeService.CreateClusterResult result = service.createCluster(COMPARTMENT, "crashed", VCN, null, null, null, null);
        when(readinessManager.probeReady(any())).thenReturn(Optional.empty());
        when(readinessManager.isClusterRunning(any())).thenReturn(false);

        service.pollReadiness();

        StoredOkeCluster cluster = service.getCluster(result.cluster().getId());
        assertEquals("FAILED", cluster.getLifecycleState());
        assertNotNull(cluster.getLifecycleDetails());
        assertEquals("FAILED", realWorkRequests.get(result.workRequestId()).getStatus());
        assertNotNull(realWorkRequests.get(result.workRequestId()).getTimeFinished());
    }

    @Test
    void pollReadinessFailsTheClusterAfterTheReadyTimeout() {
        OkeService service = realModeService();
        OkeService.CreateClusterResult result = service.createCluster(COMPARTMENT, "slow", VCN, null, null, null, null);
        when(readinessManager.probeReady(any())).thenReturn(Optional.empty());
        when(readinessManager.isClusterRunning(any())).thenReturn(true);
        clock.advanceSeconds(301);

        service.pollReadiness();

        assertEquals("FAILED", service.getCluster(result.cluster().getId()).getLifecycleState());
        assertEquals("FAILED", realWorkRequests.get(result.workRequestId()).getStatus());
    }

    @Test
    void oneClusterFailingToProbeDoesNotStarveTheOthers() {
        OkeService service = realModeService();
        StoredOkeCluster broken = service.createCluster(COMPARTMENT, "broken", VCN, null, null, null, null).cluster();
        StoredOkeCluster healthy = service.createCluster(COMPARTMENT, "healthy", VCN, null, null, null, null).cluster();
        when(readinessManager.probeReady(any())).thenAnswer(invocation -> {
            StoredOkeCluster probed = invocation.getArgument(0);
            if (probed.getId().equals(broken.getId())) {
                throw new IllegalArgumentException("unsupported URI");
            }
            return Optional.of("Q0FEQVRB");
        });

        service.pollReadiness();

        assertEquals("ACTIVE", service.getCluster(healthy.getId()).getLifecycleState());
        assertEquals("CREATING", service.getCluster(broken.getId()).getLifecycleState());
    }

    @Test
    void deletingACreatingClusterCancelsItsWorkRequest() {
        OkeService service = realModeService();
        OkeService.CreateClusterResult result = service.createCluster(COMPARTMENT, "doomed", VCN, null, null, null, null);

        service.deleteCluster(result.cluster().getId());
        service.pollReadiness();

        assertEquals("CANCELED", realWorkRequests.get(result.workRequestId()).getStatus());
    }

    @Test
    void restartKeepsReadyClustersActiveAndPollsTheOthersAgain() {
        OkeService service = realModeService();
        StoredOkeCluster ready = storedCluster("ocid1.cluster.oc1.iad.restartready", "Q0FEQVRB");
        StoredOkeCluster unready = storedCluster("ocid1.cluster.oc1.iad.restartunready", null);
        when(readinessManager.probeReady(any())).thenReturn(Optional.of("TkVXQ0E="));

        service.reconstructClusterState();
        assertEquals("ACTIVE", service.getCluster(ready.getId()).getLifecycleState());
        assertEquals("CREATING", service.getCluster(unready.getId()).getLifecycleState());

        service.pollReadiness();
        assertEquals("ACTIVE", service.getCluster(unready.getId()).getLifecycleState());
        assertEquals("TkVXQ0E=", service.getCluster(unready.getId()).getCaCertificate());
    }

    @Test
    void restartRecapturesTheCaWhenShutdownPrunedTheVolumes() {
        OkeService service = realModeService();
        StoredOkeCluster ready = storedCluster("ocid1.cluster.oc1.iad.restartpruned", "T0xEQ0E=");
        when(readinessManager.prunesVolumesOnStop()).thenReturn(true);
        when(readinessManager.probeReady(any())).thenReturn(Optional.of("TkVXQ0E="));

        service.reconstructClusterState();
        assertEquals("CREATING", service.getCluster(ready.getId()).getLifecycleState());

        service.pollReadiness();
        assertEquals("ACTIVE", service.getCluster(ready.getId()).getLifecycleState());
        assertEquals("TkVXQ0E=", service.getCluster(ready.getId()).getCaCertificate());
    }

    private StoredOkeCluster storedCluster(String id, String caCertificate) {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId(id);
        cluster.setLifecycleState("ACTIVE");
        cluster.setCaCertificate(caCertificate);
        clusters.put(id, cluster);
        return cluster;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
