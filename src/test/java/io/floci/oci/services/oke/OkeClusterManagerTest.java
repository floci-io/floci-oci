package io.floci.oci.services.oke;

import com.github.dockerjava.api.command.InspectContainerResponse;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.ContainerImageResolver.RegistryMirror;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerDetector;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.core.common.docker.CurrentContainerNetworkResolver;
import io.floci.oci.core.common.docker.DockerHostResolver;
import io.floci.oci.core.common.docker.PortAllocator;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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

    @Mock
    private DockerHostResolver dockerHostResolver;

    @Mock
    private ContainerDetector containerDetector;

    @Mock
    private CurrentContainerNetworkResolver networkResolver;

    private EmulatorConfig config;
    private ContainerBuilder.Builder specBuilder;
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
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.port()).thenReturn(4599);
        lenient().when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");

        specBuilder = mock(ContainerBuilder.Builder.class, RETURNS_DEEP_STUBS);
        lenient().when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        lenient().when(specBuilder.withName(anyString())).thenReturn(specBuilder);
        lenient().when(specBuilder.withEntrypoint(anyList())).thenReturn(specBuilder);
        lenient().when(specBuilder.withCmd(anyList())).thenReturn(specBuilder);
        lenient().when(specBuilder.withEnv(anyString(), anyString())).thenReturn(specBuilder);
        lenient().when(specBuilder.withPortBinding(anyInt(), anyInt())).thenReturn(specBuilder);
        lenient().when(specBuilder.withNamedVolume(anyString(), anyString())).thenReturn(specBuilder);
        lenient().when(specBuilder.withPrivileged(org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(specBuilder);
        lenient().when(specBuilder.withLabels(any())).thenReturn(specBuilder);
        lenient().when(specBuilder.withDockerNetwork(any())).thenReturn(specBuilder);
        lenient().when(specBuilder.withHostDockerInternalOnLinux()).thenReturn(specBuilder);
        lenient().when(specBuilder.withEmbeddedDns()).thenReturn(specBuilder);
        lenient().when(specBuilder.build()).thenReturn(new ContainerSpec("rancher/k3s:v1.30.1-k3s1"));

        manager = new OkeClusterManager(containerBuilder, lifecycleManager, portAllocator,
                dockerHostResolver, containerDetector, networkResolver, config);
    }

    @Test
    void startClusterFailureReleasesPortAndCleansUpState() {
        when(lifecycleManager.volumeConfirmedAbsent(anyString())).thenReturn(true);
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("Docker failure"));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.testcluster001");
        cluster.setApiToken("token-testcluster001");
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
    void startClusterFailureRemovesTheVolumeItCreatedWhateverThePrunePolicy() {
        when(lifecycleManager.volumeConfirmedAbsent(anyString())).thenReturn(true);
        lenient().when(config.storage().mode()).thenReturn("file");
        lenient().when(config.storage().pruneVolumesOnDelete()).thenReturn(false);
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("Docker failure"));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.testcluster004");
        cluster.setApiToken("token-testcluster004");
        cluster.setName("failing-persistent-cluster");

        assertThrows(RuntimeException.class, () -> manager.startCluster(cluster));

        String expectedVolume = ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId());
        verify(lifecycleManager).removeVolume(expectedVolume);
    }

    @Test
    void startClusterFailureKeepsAVolumeFromAnEarlierStart() {
        // A restart or a sidecar upgrade that fails must not take the cluster's data and CA with it.
        when(lifecycleManager.volumeConfirmedAbsent(anyString())).thenReturn(false);
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("Docker failure"));
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.keepvolume001");

        assertThrows(RuntimeException.class, () -> manager.startCluster(cluster));

        verify(lifecycleManager, never()).removeVolume(anyString());
        verify(portAllocator).release(6460);
        assertEquals("FAILED", cluster.getLifecycleState());
    }

    @Test
    void startClusterFailureKeepsTheVolumeWhenItCouldNotBeInspected() {
        when(lifecycleManager.volumeConfirmedAbsent(anyString())).thenThrow(new RuntimeException("daemon gone"));
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.keepvolume003");

        assertThrows(RuntimeException.class, () -> manager.startCluster(cluster));

        verify(lifecycleManager, never()).removeVolume(anyString());
    }

    @Test
    void aFailedUpgradeOfARunningSidecarWithoutTheSanKeepsItsVolume() {
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.keepvolume002");
        String webhookKubeconfig = manager.webhookKubeconfig(cluster);
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        when(lifecycleManager.containerEnv(anyString())).thenReturn(List.of(
                OkeClusterManager.API_TOKEN_ENV + "=" + cluster.getApiToken(),
                OkeClusterManager.WEBHOOK_KUBECONFIG_ENV + "=" + webhookKubeconfig));
        when(lifecycleManager.volumeConfirmedAbsent(anyString())).thenReturn(false);
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("Docker failure"));

        assertThrows(RuntimeException.class, () -> manager.startCluster(cluster));

        verify(lifecycleManager, never()).removeVolume(anyString());
    }

    @Test
    void clusterRenameDoesNotBreakStopCluster() {
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-123", Map.of()));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.testcluster002");
        cluster.setApiToken("token-testcluster002");
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
        cluster.setApiToken("token-testcluster006");
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
        cluster1.setApiToken("token-cluster001");
        cluster1.setName("cluster-1");

        StoredOkeCluster cluster2 = new StoredOkeCluster();
        cluster2.setId("ocid1.cluster.oc1.iad.cluster002");
        cluster2.setApiToken("token-cluster002");
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
        cluster.setApiToken("token-cluster005");
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
        cluster.setApiToken("token-testcluster003");
        cluster.setName("persistent-cluster");

        manager.startCluster(cluster);
        manager.stopCluster(cluster);

        String expectedContainer = ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId());
        String expectedVolume = ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId());

        verify(lifecycleManager, times(2)).removeIfExists(expectedContainer);
        verify(lifecycleManager, never()).removeVolume(expectedVolume);
        verify(portAllocator).release(6443);
    }

    @Test
    void resetRemovesEveryVolumeEvenWhenStorageIsPersistentAndPruneIsFalse() {
        // Lenient: the point is that reset never consults the policy.
        lenient().when(config.storage().mode()).thenReturn("file");
        lenient().when(config.storage().pruneVolumesOnDelete()).thenReturn(false);
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.cluster007");
        cluster.setApiToken("token-cluster007");
        cluster.setName("reset-cluster");
        manager.startCluster(cluster);

        manager.clear();

        verify(lifecycleManager).removeVolume(ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId()));
        verify(portAllocator).release(6443);
    }

    @Test
    void shutdownKeepsVolumesWhenStorageIsPersistentAndPruneIsFalse() {
        when(config.storage().mode()).thenReturn("file");
        when(config.storage().pruneVolumesOnDelete()).thenReturn(false);
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.cluster008");
        cluster.setApiToken("token-cluster008");
        cluster.setName("persistent-across-restart");
        manager.startCluster(cluster);

        manager.shutdown();

        // The k3s data, and with it the cluster's CA, must survive a restart of floci-oci.
        verify(lifecycleManager, never()).removeVolume(anyString());
        verify(lifecycleManager, times(2)).removeIfExists(ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId()));
        verify(portAllocator).release(6443);
    }

    @Test
    void startClusterCreatesTheLabelledVolumeBeforeTheContainer() {
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.cluster009");
        cluster.setApiToken("token-cluster009");
        cluster.setName("labelled-volume");

        manager.startCluster(cluster);

        InOrder order = inOrder(lifecycleManager);
        order.verify(lifecycleManager).ensureVolume(ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId()));
        order.verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void registerExistingClusterRestartsContainerAndReservesPort() {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.reconstructed001");
        cluster.setApiToken("token-reconstructed001");
        cluster.setHostPort(6445);
        cluster.setName("reconstructed-cluster");

        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(false);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-999", Map.of()));

        manager.registerExistingCluster(cluster);

        verify(portAllocator).markReserved(6445);
        String expectedContainer = ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId());
        verify(lifecycleManager).removeIfExists(expectedContainer);
        verify(lifecycleManager).createAndStart(any());
        assertEquals("https://127.0.0.1:6445", cluster.getEndpoints().get("kubernetes"));
    }

    @Test
    void startClusterRunsK3sServerWithTheClusterApiToken() {
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-789", Map.of()));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.servermode001");
        cluster.setApiToken("token-servermode001");

        cluster.setTenancyId("ocid1.tenancy.oc1..servermode");

        manager.startCluster(cluster);

        verify(specBuilder).withEntrypoint(OkeClusterManager.K3S_ENTRYPOINT);
        verify(specBuilder).withCmd(List.of("k3s", "server", "--disable=traefik",
                "--kube-apiserver-arg=token-auth-file=" + OkeClusterManager.TOKEN_FILE,
                "--kube-apiserver-arg=authentication-token-webhook-config-file=" + OkeClusterManager.WEBHOOK_FILE,
                "--kube-apiserver-arg=authentication-token-webhook-version=v1",
                "--kube-apiserver-arg=authentication-token-webhook-cache-ttl=30s"));
        verify(specBuilder).withEnv(OkeClusterManager.API_TOKEN_ENV, "token-servermode001");
        verify(specBuilder).withEnv(OkeClusterManager.WEBHOOK_KUBECONFIG_ENV, manager.webhookKubeconfig(cluster));
        verify(specBuilder).withEnv(OkeClusterManager.TLS_SAN_ENV,
                ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId()));
        verify(specBuilder).withDockerNetwork(Optional.empty());
        verify(specBuilder).withHostDockerInternalOnLinux();
        assertNull(cluster.getLifecycleState(), "real mode leaves ACTIVE to the readiness poller");
    }

    @Test
    void k3sEntrypointWritesTokenAndWebhookFilesFromTheEnvironment() {
        String script = OkeClusterManager.K3S_ENTRYPOINT.get(2);
        assertEquals(List.of("sh", "-c"), OkeClusterManager.K3S_ENTRYPOINT.subList(0, 2));
        assertTrue(script.contains("\"$" + OkeClusterManager.API_TOKEN_ENV + "\""));
        assertTrue(script.contains("\"$" + OkeClusterManager.WEBHOOK_KUBECONFIG_ENV + "\" > "
                + OkeClusterManager.WEBHOOK_FILE));
        assertTrue(script.contains("system:masters"));
        assertTrue(script.endsWith("exec /bin/k3s \"$@\" --tls-san=\"$" + OkeClusterManager.TLS_SAN_ENV + "\""));
    }

    @Test
    void webhookKubeconfigScopesTheUrlByTenancyAndClusterInThePath() {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.webhook001");
        cluster.setTenancyId("ocid1.tenancy.oc1..webhooktenancy");

        String kubeconfig = manager.webhookKubeconfig(cluster);

        assertTrue(kubeconfig.contains("server: http://host.docker.internal:4599"
                + "/_floci-oci/oke/token-webhook/ocid1.tenancy.oc1..webhooktenancy/ocid1.cluster.oc1.iad.webhook001\n"),
                kubeconfig);
        assertFalse(kubeconfig.contains("?"), "client-go drops the query, so the scope must be in the path");
        assertFalse(kubeconfig.contains("insecure-skip-tls-verify"));
    }

    @Test
    void webhookStaysPlainHttpWhenFlociServesTls() {
        lenient().when(config.tls().enabled()).thenReturn(true);
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.webhook002");
        cluster.setTenancyId("ocid1.tenancy.oc1..webhooktenancy");

        String kubeconfig = manager.webhookKubeconfig(cluster);

        assertTrue(kubeconfig.contains("server: http://host.docker.internal:4599/"), kubeconfig);
        assertFalse(kubeconfig.contains("insecure-skip-tls-verify"), kubeconfig);
    }

    @Test
    void apiServerUrlIsThePublishedPortWhenFlociRunsOnTheHost() throws Exception {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.3startswithadigit");
        cluster.setHostPort(6450);
        when(containerDetector.isRunningInContainer()).thenReturn(false);

        assertEquals("https://127.0.0.1:6450", manager.apiServerUrl(cluster));
    }

    @Test
    void apiServerUrlIsTheSidecarIpOnFlocisOwnNetworkWhenFlociRunsInDocker() throws Exception {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.3startswithadigit");
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("compat-net"));
        when(lifecycleManager.resolveEndpoint(anyString(), eq(6443), eq("compat-net")))
                .thenReturn(new ContainerLifecycleManager.EndpointInfo("172.18.0.5", 6443));

        assertEquals("https://172.18.0.5:6443", manager.apiServerUrl(cluster));
    }

    @Test
    void apiServerUrlPrefersTheConfiguredDockerNetworkTheSidecarJoined() throws Exception {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.confignet001");
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.services().dockerNetwork()).thenReturn(Optional.of("shared-net"));
        lenient().when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("floci-net"));
        when(lifecycleManager.resolveEndpoint(anyString(), eq(6443), eq("shared-net")))
                .thenReturn(new ContainerLifecycleManager.EndpointInfo("172.19.0.7", 6443));

        assertEquals("https://172.19.0.7:6443", manager.apiServerUrl(cluster));
    }

    @Test
    void apiServerUrlReportsAVanishedSidecarAsAnIoFailure() {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.vanished001");
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(networkResolver.resolveNetworkName()).thenReturn(Optional.empty());
        when(lifecycleManager.resolveEndpoint(anyString(), eq(6443), isNull())).thenThrow(new IllegalStateException("gone"));

        assertThrows(IOException.class, () -> manager.apiServerUrl(cluster));
    }

    @Test
    void startClusterAdoptsARunningSidecarCreatedForThisCluster() {
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.adopt001");
        String webhookKubeconfig = manager.webhookKubeconfig(cluster);
        String tlsSan = containerName(cluster);
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        when(lifecycleManager.containerEnv(anyString())).thenReturn(List.of(
                OkeClusterManager.API_TOKEN_ENV + "=" + cluster.getApiToken(),
                OkeClusterManager.WEBHOOK_KUBECONFIG_ENV + "=" + webhookKubeconfig,
                OkeClusterManager.TLS_SAN_ENV + "=" + tlsSan));
        when(lifecycleManager.containerLabels(anyString())).thenReturn(
                Map.of(ContainerStorageHelper.REGION_LABEL, "us-ashburn-1"));

        manager.startCluster(cluster);

        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void startClusterRecreatesARunningSidecarLabelledWithAnotherRegion() {
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.phx.adopt003");
        cluster.setRegion("us-phoenix-1");
        String webhookKubeconfig = manager.webhookKubeconfig(cluster);
        String tlsSan = containerName(cluster);
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        when(lifecycleManager.containerEnv(anyString())).thenReturn(List.of(
                OkeClusterManager.API_TOKEN_ENV + "=" + cluster.getApiToken(),
                OkeClusterManager.WEBHOOK_KUBECONFIG_ENV + "=" + webhookKubeconfig,
                OkeClusterManager.TLS_SAN_ENV + "=" + tlsSan));
        when(lifecycleManager.containerLabels(anyString())).thenReturn(
                Map.of(ContainerStorageHelper.REGION_LABEL, "us-ashburn-1"));
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-new", Map.of()));

        manager.startCluster(cluster);

        verify(lifecycleManager).removeIfExists(anyString());
        verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void startClusterRecreatesARunningSidecarWithoutTheCurrentWebhook() {
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.adopt002");
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        when(lifecycleManager.containerEnv(anyString())).thenReturn(List.of(
                OkeClusterManager.API_TOKEN_ENV + "=" + cluster.getApiToken()));
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-new", Map.of()));

        manager.startCluster(cluster);

        verify(lifecycleManager).removeIfExists(anyString());
        verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void startClusterRecreatesARunningSidecarWithoutTheContainerNameSan() {
        // Created by a floci-oci that did not add the SAN: its certificate cannot serve network mode.
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.adopt004");
        String webhookKubeconfig = manager.webhookKubeconfig(cluster);
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        when(lifecycleManager.containerEnv(anyString())).thenReturn(List.of(
                OkeClusterManager.API_TOKEN_ENV + "=" + cluster.getApiToken(),
                OkeClusterManager.WEBHOOK_KUBECONFIG_ENV + "=" + webhookKubeconfig));
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-new", Map.of()));

        manager.startCluster(cluster);

        verify(lifecycleManager).removeIfExists(anyString());
        verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void networkModeAdvertisesTheSidecarContainerNameWhenFlociRunsInDocker() {
        when(config.services().oke().endpointMode()).thenReturn("network");
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        lenient().when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("floci_oci_default"));
        StoredOkeCluster cluster = startedCluster("ocid1.cluster.oc1.iad.netmode001", 6470);

        assertEquals("https://" + containerName(cluster) + ":6443", cluster.getEndpoints().get("kubernetes"));
        assertEquals("10.0.0.10:6470", cluster.getEndpoints().get("privateEndpoint"));
    }

    @Test
    void networkModeFallsBackToTheHostPortOnANetworkWithoutNameLookup() {
        when(config.services().oke().endpointMode()).thenReturn("network");
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        lenient().when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("bridge"));
        StoredOkeCluster cluster = startedCluster("ocid1.cluster.oc1.iad.netmode003", 6473);

        assertEquals("https://127.0.0.1:6473", cluster.getEndpoints().get("kubernetes"));
    }

    @Test
    void networkModeUsesTheConfiguredDockerNetworkWhenItHasNameLookup() {
        when(config.services().oke().endpointMode()).thenReturn("network");
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.services().dockerNetwork()).thenReturn(Optional.of("shared-net"));
        lenient().when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("bridge"));
        StoredOkeCluster cluster = startedCluster("ocid1.cluster.oc1.iad.netmode004", 6474);

        assertEquals("https://" + containerName(cluster) + ":6443", cluster.getEndpoints().get("kubernetes"));
    }

    @Test
    void networkModeFallsBackToTheHostPortWhenFlociRunsOnTheHost() {
        lenient().when(config.services().oke().endpointMode()).thenReturn("NETWORK");
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        StoredOkeCluster cluster = startedCluster("ocid1.cluster.oc1.iad.netmode002", 6471);

        assertEquals("https://127.0.0.1:6471", cluster.getEndpoints().get("kubernetes"));
    }

    @Test
    void hostModeAdvertisesTheHostPortEvenWhenFlociRunsInDocker() {
        when(config.services().oke().endpointMode()).thenReturn("host");
        lenient().when(containerDetector.isRunningInContainer()).thenReturn(true);
        StoredOkeCluster cluster = startedCluster("ocid1.cluster.oc1.iad.hostmode001", 6472);

        assertEquals("https://127.0.0.1:6472", cluster.getEndpoints().get("kubernetes"));
    }

    @Test
    void endpointModeAcceptsHostAndNetworkInAnyCaseAndRejectsTheRest() {
        OkeClusterManager.validateEndpointMode("host");
        OkeClusterManager.validateEndpointMode("Network");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> OkeClusterManager.validateEndpointMode("public"));
        assertTrue(e.getMessage().contains("floci-oci.services.oke.endpoint-mode"), e.getMessage());
    }

    private StoredOkeCluster startedCluster(String id, int hostPort) {
        StoredOkeCluster cluster = adoptableCluster(id);
        cluster.setHostPort(hostPort);
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(false);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-" + hostPort, Map.of()));
        manager.startCluster(cluster);
        return cluster;
    }

    private String containerName(StoredOkeCluster cluster) {
        return ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId());
    }

    private StoredOkeCluster adoptableCluster(String id) {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId(id);
        cluster.setApiToken("token-" + id);
        cluster.setTenancyId("ocid1.tenancy.oc1..adopt");
        cluster.setHostPort(6460);
        return cluster;
    }

    @Test
    void containerNamesWithADigitLeadingLabelAreNotValidUriHosts() {
        // Why apiServerUrl dials the sidecar's IP in Docker instead of its container name.
        String name = ContainerStorageHelper.dockerName(config, "oke-ocid1.cluster.oc1.iad.3gij5xynypbmq");

        assertNull(URI.create("https://" + name + ":6443").getHost());
    }

    @Test
    void startClusterWithoutApiTokenFailsBeforeTouchingDocker() {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.notoken001");

        assertThrows(IllegalStateException.class, () -> manager.startCluster(cluster));

        verify(portAllocator, never()).allocate(anyInt(), anyInt());
        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void startClusterLabelsContainerWithClusterIdentity() {
        OkeClusterManager labelledManager = new OkeClusterManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class), null), lifecycleManager, portAllocator,
                dockerHostResolver, containerDetector, networkResolver, config);
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.labelled001");
        cluster.setCompartmentId("ocid1.compartment.oc1..labelled");
        cluster.setName("labelled-cluster");
        cluster.setApiToken("token-labelled001");

        labelledManager.startCluster(cluster);

        ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(spec.capture());
        assertEquals(
                Map.of(
                        "io.floci", "oci",
                        "io.floci.service", "oke",
                        "io.floci.resource-id", "ocid1.cluster.oc1.iad.labelled001",
                        "io.floci.compartment", "ocid1.compartment.oc1..labelled",
                        "io.floci.region", "us-ashburn-1"),
                spec.getValue().labels());
    }

    @Test
    void startClusterLabelsContainerWithTheClusterRegion() {
        OkeClusterManager labelledManager = new OkeClusterManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class), null), lifecycleManager, portAllocator,
                dockerHostResolver, containerDetector, networkResolver, config);
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.phx.labelled002");
        cluster.setCompartmentId("ocid1.compartment.oc1..labelled");
        cluster.setName("phoenix-cluster");
        cluster.setApiToken("token-labelled002");
        cluster.setRegion("us-phoenix-1");

        labelledManager.startCluster(cluster);

        ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(spec.capture());
        assertEquals("us-phoenix-1", spec.getValue().labels().get("io.floci.region"));
    }

    private static final RegistryMirror IAD_MIRROR = new RegistryMirror(
            List.of("iad.ocir.localhost.floci.io", "iad.ocir.localhost.floci.io:4599"),
            "http://host.docker.internal:5200", "iad/");

    private OkeClusterManager managerWithMirror(Function<String, Optional<RegistryMirror>> mirrors) {
        return new OkeClusterManager(containerBuilder, lifecycleManager, portAllocator,
                dockerHostResolver, containerDetector, networkResolver, config, mirrors);
    }

    @Test
    void k3sEntrypointWritesRegistriesYamlOnlyWhenTheEnvironmentCarriesOneAndNoRefreshWroteIt() {
        String script = OkeClusterManager.K3S_ENTRYPOINT.get(2);
        assertTrue(script.contains("if [ -n \"$" + OkeClusterManager.REGISTRIES_ENV + "\" ] && [ ! -f "
                + OkeClusterManager.REGISTRIES_FILE + " ]; then"));
        assertTrue(script.contains("\"$" + OkeClusterManager.REGISTRIES_ENV + "\" > "
                + OkeClusterManager.REGISTRIES_FILE + "; fi"));
        assertTrue(script.indexOf(OkeClusterManager.REGISTRIES_FILE) < script.indexOf("exec /bin/k3s"));
    }

    @Test
    void registriesYamlMirrorsEachOcirHostToTheRegistryWithTheRegionPrefix() {
        String yaml = managerWithMirror(region -> Optional.of(IAD_MIRROR)).registriesYaml("us-ashburn-1");

        assertEquals("""
                mirrors:
                  "iad.ocir.localhost.floci.io":
                    endpoint:
                      - "http://host.docker.internal:5200"
                    rewrite:
                      "^(.*)$": "iad/$1"
                  "iad.ocir.localhost.floci.io:4599":
                    endpoint:
                      - "http://host.docker.internal:5200"
                    rewrite:
                      "^(.*)$": "iad/$1"
                """, yaml);
    }

    @Test
    void registriesYamlIsAbsentWithoutARegistryOrWhenItCannotStart() {
        assertNull(manager.registriesYaml("us-ashburn-1"));
        assertNull(managerWithMirror(region -> {
            throw new IllegalStateException("registry down");
        }).registriesYaml("us-ashburn-1"));
    }

    @Test
    void startClusterPassesTheRegistryMirrorForTheClusterRegion() {
        List<String> regions = new ArrayList<>();
        OkeClusterManager mirrored = managerWithMirror(region -> {
            regions.add(region);
            return Optional.of(IAD_MIRROR);
        });
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.mirror001");
        cluster.setHostPort(0);

        mirrored.startCluster(cluster);

        assertEquals(List.of("us-ashburn-1"), regions);
        verify(specBuilder).withEnv(OkeClusterManager.REGISTRIES_ENV, mirrored.registriesYaml("us-ashburn-1"));
    }

    @Test
    void startClusterSetsNoRegistriesWithoutAMirror() {
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.mirror002");
        cluster.setHostPort(0);

        manager.startCluster(cluster);

        verify(specBuilder, never()).withEnv(eq(OkeClusterManager.REGISTRIES_ENV), anyString());
    }

    @Test
    void startClusterRecreatesARunningSidecarWhoseRegistryMirrorChanged() {
        OkeClusterManager mirrored = managerWithMirror(region -> Optional.of(IAD_MIRROR));
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.mirror003");
        String tlsSan = containerName(cluster);
        String webhookKubeconfig = mirrored.webhookKubeconfig(cluster);
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        when(lifecycleManager.containerEnv(anyString())).thenReturn(List.of(
                OkeClusterManager.API_TOKEN_ENV + "=" + cluster.getApiToken(),
                OkeClusterManager.WEBHOOK_KUBECONFIG_ENV + "=" + webhookKubeconfig,
                OkeClusterManager.TLS_SAN_ENV + "=" + tlsSan,
                OkeClusterManager.REGISTRIES_ENV + "=mirrors: {}"));
        lenient().when(lifecycleManager.containerLabels(anyString())).thenReturn(
                Map.of(ContainerStorageHelper.REGION_LABEL, "us-ashburn-1"));
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-new", Map.of()));

        mirrored.startCluster(cluster);

        verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void startClusterAdoptsARunningSidecarWithTheCurrentRegistryMirror() {
        OkeClusterManager mirrored = managerWithMirror(region -> Optional.of(IAD_MIRROR));
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.mirror004");
        String tlsSan = containerName(cluster);
        String webhookKubeconfig = mirrored.webhookKubeconfig(cluster);
        String registries = mirrored.registriesYaml("us-ashburn-1");
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        when(lifecycleManager.containerEnv(anyString())).thenReturn(List.of(
                OkeClusterManager.API_TOKEN_ENV + "=" + cluster.getApiToken(),
                OkeClusterManager.WEBHOOK_KUBECONFIG_ENV + "=" + webhookKubeconfig,
                OkeClusterManager.TLS_SAN_ENV + "=" + tlsSan,
                OkeClusterManager.REGISTRIES_ENV + "=" + registries));
        when(lifecycleManager.containerLabels(anyString())).thenReturn(
                Map.of(ContainerStorageHelper.REGION_LABEL, "us-ashburn-1"));

        mirrored.startCluster(cluster);

        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void startClusterRecreatesASidecarWithAMirrorThatIsNoLongerServed() {
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.mirror005");
        String tlsSan = containerName(cluster);
        String webhookKubeconfig = manager.webhookKubeconfig(cluster);
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        when(lifecycleManager.containerEnv(anyString())).thenReturn(List.of(
                OkeClusterManager.API_TOKEN_ENV + "=" + cluster.getApiToken(),
                OkeClusterManager.WEBHOOK_KUBECONFIG_ENV + "=" + webhookKubeconfig,
                OkeClusterManager.TLS_SAN_ENV + "=" + tlsSan,
                OkeClusterManager.REGISTRIES_ENV + "=mirrors: {}"));
        lenient().when(lifecycleManager.containerLabels(anyString())).thenReturn(
                Map.of(ContainerStorageHelper.REGION_LABEL, "us-ashburn-1"));
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-new", Map.of()));

        manager.startCluster(cluster);

        verify(lifecycleManager).createAndStart(any());
    }

    private static final RegistryMirror MOVED_IAD_MIRROR = new RegistryMirror(
            List.of("iad.ocir.localhost.floci.io", "iad.ocir.localhost.floci.io:4599"),
            "http://host.docker.internal:5201", "iad/");

    private static final List<String> CAT_REGISTRIES = List.of("cat", OkeClusterManager.REGISTRIES_FILE);

    private void sidecarEntrypoint(List<String> entrypoint) {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        lenient().when(inspect.getConfig().getEntrypoint()).thenReturn(entrypoint.toArray(String[]::new));
        lenient().when(lifecycleManager.inspect(anyString())).thenReturn(Optional.of(inspect));
    }

    private void liveRegistriesFile(String content) {
        lenient().when(lifecycleManager.exec(anyString(), eq(List.of()), eq(CAT_REGISTRIES), any()))
                .thenReturn(content == null
                        ? new ContainerLifecycleManager.ExecResult(1, "", "cat: no such file")
                        : new ContainerLifecycleManager.ExecResult(0, content, ""));
    }

    @Test
    void mirrorRefreshLeavesASidecarAloneWithoutARegistryOrWhenItIsNotRunning() {
        String sidecar = "floci-oci-oke-refresh001";
        assertEquals(OkeClusterManager.MirrorRefresh.NONE, manager.mirrorRefresh(sidecar, null));

        when(lifecycleManager.isContainerRunning(sidecar)).thenReturn(false);
        assertEquals(OkeClusterManager.MirrorRefresh.NONE, manager.mirrorRefresh(sidecar, "mirrors: {}"));
        verify(lifecycleManager, never()).exec(anyString(), anyList(), anyList(), any());
    }

    @Test
    void mirrorRefreshLeavesASidecarAloneWhoseFileAlreadyMirrorsToTheRegistry() {
        String registries = managerWithMirror(region -> Optional.of(MOVED_IAD_MIRROR)).registriesYaml("us-ashburn-1");
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        liveRegistriesFile(registries + "\n");

        assertEquals(OkeClusterManager.MirrorRefresh.NONE, manager.mirrorRefresh("floci-oci-oke-refresh002", registries));
    }

    @Test
    void mirrorRefreshRestartsASidecarWhoseEntrypointKeepsTheRewrittenFile() {
        String registries = managerWithMirror(region -> Optional.of(MOVED_IAD_MIRROR)).registriesYaml("us-ashburn-1");
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        liveRegistriesFile(managerWithMirror(region -> Optional.of(IAD_MIRROR)).registriesYaml("us-ashburn-1") + "\n");
        sidecarEntrypoint(OkeClusterManager.K3S_ENTRYPOINT);

        assertEquals(OkeClusterManager.MirrorRefresh.RESTART,
                manager.mirrorRefresh("floci-oci-oke-refresh003", registries));
    }

    @Test
    void mirrorRefreshRestartsASidecarThatHasNoFileYet() {
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        liveRegistriesFile(null);
        sidecarEntrypoint(OkeClusterManager.K3S_ENTRYPOINT);

        assertEquals(OkeClusterManager.MirrorRefresh.RESTART,
                manager.mirrorRefresh("floci-oci-oke-refresh004", "mirrors: {}"));
    }

    @Test
    void mirrorRefreshRecreatesASidecarWhoseOlderEntrypointRewritesTheFileFromTheEnv() {
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        liveRegistriesFile("mirrors: {}\n");
        sidecarEntrypoint(List.of("sh", "-c", "exec /bin/k3s \"$@\""));

        assertEquals(OkeClusterManager.MirrorRefresh.RECREATE,
                manager.mirrorRefresh("floci-oci-oke-refresh005", "mirrors:\n  other: {}"));
    }

    @Test
    void aMovedRegistryRewritesTheFileAndRestartsTheSidecarOffTheCallerThread() {
        List<RegistryMirror> mirror = new ArrayList<>(List.of(IAD_MIRROR));
        OkeClusterManager mirrored = managerWithMirror(region -> Optional.of(mirror.get(0)));
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.refresh006");
        cluster.setHostPort(0);
        mirrored.startCluster(cluster);
        String sidecar = containerName(cluster);

        mirror.set(0, MOVED_IAD_MIRROR);
        String moved = mirrored.registriesYaml("us-ashburn-1");
        when(lifecycleManager.isContainerRunning(sidecar)).thenReturn(true);
        liveRegistriesFile(managerWithMirror(region -> Optional.of(IAD_MIRROR)).registriesYaml("us-ashburn-1") + "\n");
        sidecarEntrypoint(OkeClusterManager.K3S_ENTRYPOINT);
        Thread caller = Thread.currentThread();
        List<Thread> restartedOn = new CopyOnWriteArrayList<>();
        when(lifecycleManager.exec(eq(sidecar), eq(List.of(OkeClusterManager.REGISTRIES_ENV + "=" + moved)),
                eq(OkeClusterManager.WRITE_REGISTRIES), any()))
                .thenReturn(new ContainerLifecycleManager.ExecResult(0, "", ""));
        doAnswer(invocation -> {
            restartedOn.add(Thread.currentThread());
            return null;
        }).when(lifecycleManager).restart(eq(sidecar), anyInt());

        mirrored.registryMirrorMoved();

        verify(lifecycleManager, timeout(5000)).restart(eq(sidecar), anyInt());
        assertFalse(restartedOn.contains(caller), "the refresh must not run on the thread that moved the registry");
        verify(lifecycleManager, times(1)).createAndStart(any());
    }

    @Test
    void aMovedRegistryRecreatesASidecarFromAnOlderFlociOnItsVolume() {
        List<RegistryMirror> mirror = new ArrayList<>(List.of(IAD_MIRROR));
        OkeClusterManager mirrored = managerWithMirror(region -> Optional.of(mirror.get(0)));
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.refresh007");
        cluster.setHostPort(0);
        mirrored.startCluster(cluster);
        String sidecar = containerName(cluster);
        String original = mirrored.registriesYaml("us-ashburn-1");

        mirror.set(0, MOVED_IAD_MIRROR);
        when(lifecycleManager.isContainerRunning(sidecar)).thenReturn(true);
        liveRegistriesFile(original + "\n");
        sidecarEntrypoint(List.of("sh", "-c", "exec /bin/k3s \"$@\""));
        when(lifecycleManager.containerEnv(sidecar)).thenReturn(List.of(
                OkeClusterManager.API_TOKEN_ENV + "=" + cluster.getApiToken(),
                OkeClusterManager.REGISTRIES_ENV + "=" + original));

        mirrored.refreshRegistryMirrors();

        verify(lifecycleManager, times(2)).createAndStart(any());
        verify(lifecycleManager, never()).restart(anyString(), anyInt());
        verify(lifecycleManager, never()).removeVolume(anyString());
        assertEquals(6443, cluster.getHostPort());
    }

    @Test
    void aMovedRegistryIsIgnoredWithoutActiveClusters() {
        manager.registryMirrorMoved();
        manager.refreshRegistryMirrors();

        verify(lifecycleManager, never()).isContainerRunning(anyString());
        verify(lifecycleManager, never()).restart(anyString(), anyInt());
    }
}
