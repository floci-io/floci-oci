package io.floci.oci.services.oke;

import io.floci.oci.config.EmulatorConfig;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        lenient().when(specBuilder.build()).thenReturn(new ContainerSpec("rancher/k3s:v1.30.1-k3s1"));

        manager = new OkeClusterManager(containerBuilder, lifecycleManager, portAllocator,
                dockerHostResolver, containerDetector, networkResolver, config);
    }

    @Test
    void startClusterFailureReleasesPortAndCleansUpState() {
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
    void startClusterFailureRemovesVolumeUnconditionally() {
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
        assertTrue(script.endsWith("exec /bin/k3s \"$@\""));
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
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        when(lifecycleManager.containerEnv(anyString())).thenReturn(List.of(
                OkeClusterManager.API_TOKEN_ENV + "=" + cluster.getApiToken(),
                OkeClusterManager.WEBHOOK_KUBECONFIG_ENV + "=" + webhookKubeconfig));

        manager.startCluster(cluster);

        verify(lifecycleManager, never()).createAndStart(any());
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
}
