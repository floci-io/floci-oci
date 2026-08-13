package io.floci.oci.services.oke;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.core.common.docker.PortAllocator;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OkeClusterManagerTest {

    @Mock
    private ContainerLifecycleManager lifecycleManager;

    @Mock
    private PortAllocator portAllocator;

    @Mock
    private ContainerBuilder containerBuilder;

    private EmulatorConfig config;
    private OkeClusterManager manager;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.services().oke().mock()).thenReturn(false);
        lenient().when(config.services().oke().apiServerBasePort()).thenReturn(6443);
        lenient().when(config.services().oke().apiServerMaxPort()).thenReturn(6543);
        lenient().when(config.services().oke().defaultImage()).thenReturn("rancher/k3s:v1.30.1-k3s1");
        lenient().when(config.storage().mode()).thenReturn("memory");
        lenient().when(config.storage().pruneVolumesOnDelete()).thenReturn(true);

        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, RETURNS_DEEP_STUBS);
        lenient().when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        lenient().when(specBuilder.withName(anyString())).thenReturn(specBuilder);
        lenient().when(specBuilder.withEnv(anyString(), anyString())).thenReturn(specBuilder);
        lenient().when(specBuilder.withPortBinding(anyInt(), anyInt())).thenReturn(specBuilder);
        lenient().when(specBuilder.withNamedVolume(anyString(), anyString())).thenReturn(specBuilder);
        lenient().when(specBuilder.build()).thenReturn(new ContainerSpec("rancher/k3s:v1.30.1-k3s1"));

        manager = new OkeClusterManager(containerBuilder, lifecycleManager, portAllocator, config);
    }

    @Test
    void startClusterFailureReleasesPortAndCleansUpState() {
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("Docker failure"));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.testcluster001");
        cluster.setName("failing-cluster");

        assertThrows(RuntimeException.class, () -> manager.startCluster(cluster));

        String expectedContainer = ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId());
        String expectedVolume = ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId());

        verify(lifecycleManager, times(2)).removeIfExists(expectedContainer);
        verify(lifecycleManager).removeVolume(expectedVolume);
        verify(portAllocator).release(6443);
        assertEquals(0, cluster.getHostPort());
        assertEquals("FAILED", cluster.getLifecycleState());
    }

    @Test
    void startClusterFailureRemovesVolumeUnconditionally() {
        lenient().when(config.storage().mode()).thenReturn("file");
        lenient().when(config.storage().pruneVolumesOnDelete()).thenReturn(false);
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("Docker failure"));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.testcluster004");
        cluster.setName("failing-persistent-cluster");

        assertThrows(RuntimeException.class, () -> manager.startCluster(cluster));

        String expectedVolume = ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId());
        verify(lifecycleManager).removeVolume(expectedVolume);
    }

    @Test
    void clusterRenameDoesNotBreakStopCluster() {
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-123", Map.of()));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.testcluster002");
        cluster.setName("original-name");

        manager.startCluster(cluster);

        // Rename the display name on the cluster object
        cluster.setName("renamed-display-name");

        manager.stopCluster(cluster);

        String expectedContainer = ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId());
        String expectedVolume = ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId());

        // Called once in startCluster and once in stopCluster
        verify(lifecycleManager, times(2)).removeIfExists(expectedContainer);
        verify(lifecycleManager).removeVolume(expectedVolume);
        verify(portAllocator).release(6443);
    }

    @Test
    void stopClusterFailureDoesNotReleasePort() {
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.testcluster006");
        cluster.setName("failing-stop-cluster");

        manager.startCluster(cluster);

        String expectedContainer = ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId());
        org.mockito.Mockito.doThrow(new RuntimeException("Container removal failure"))
                .when(lifecycleManager).removeIfExists(expectedContainer);

        assertThrows(RuntimeException.class, () -> manager.stopCluster(cluster));

        verify(portAllocator, never()).release(6443);
    }

    @Test
    void clearStopsActiveContainersAndReleasesPorts() {
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443).thenReturn(6444);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));

        StoredOkeCluster cluster1 = new StoredOkeCluster();
        cluster1.setId("ocid1.cluster.oc1.iad.cluster001");
        cluster1.setName("cluster-1");

        StoredOkeCluster cluster2 = new StoredOkeCluster();
        cluster2.setId("ocid1.cluster.oc1.iad.cluster002");
        cluster2.setName("cluster-2");

        manager.startCluster(cluster1);
        manager.startCluster(cluster2);

        manager.clear();

        verify(portAllocator).release(6443);
        verify(portAllocator).release(6444);
        verify(lifecycleManager, times(2)).removeIfExists(ContainerStorageHelper.dockerName(config, "oke-" + cluster1.getId()));
        verify(lifecycleManager, times(2)).removeIfExists(ContainerStorageHelper.dockerName(config, "oke-" + cluster2.getId()));
    }

    @Test
    void clearReleasesPortsEvenIfVolumeCleanupThrowsException() {
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        org.mockito.Mockito.doThrow(new RuntimeException("Volume removal error")).when(lifecycleManager).removeVolume(anyString());

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.cluster005");
        cluster.setName("cluster-volume-error");

        manager.startCluster(cluster);

        manager.clear();

        verify(portAllocator).release(6443);
    }

    @Test
    void stopClusterRetainsVolumeWhenStorageIsPersistentAndPruneIsFalse() {
        when(config.storage().mode()).thenReturn("file");
        when(config.storage().pruneVolumesOnDelete()).thenReturn(false);
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-123", Map.of()));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.testcluster003");
        cluster.setName("persistent-cluster");

        manager.startCluster(cluster);
        manager.stopCluster(cluster);

        String expectedContainer = ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId());
        String expectedVolume = ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId());

        verify(lifecycleManager, times(2)).removeIfExists(expectedContainer);
        verify(lifecycleManager, never()).removeVolume(expectedVolume);
        verify(portAllocator).release(6443);
    }
}
