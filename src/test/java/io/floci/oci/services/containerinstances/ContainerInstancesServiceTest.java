package io.floci.oci.services.containerinstances;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.NetworkReferences;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.containerinstances.model.StoredContainer;
import io.floci.oci.services.containerinstances.model.StoredContainerInstance;
import io.floci.oci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ContainerInstancesServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..unittesttenancy";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..unittestcompartment";
    private static final String AD = "Floc:US-ASHBURN-AD-1";
    private static final String SUBNET = "ocid1.subnet.oc1.iad.unittestsubnet";
    private static final String VNIC = "ocid1.vnic.oc1.iad.unittestvnic";

    private EmulatorConfig config;
    private WorkRequestService workRequests;
    private NetworkReferences network;
    private ContainerInstanceManager manager;
    private InMemoryStorage<String, StoredContainerInstance> instances;
    private InMemoryStorage<String, StoredContainer> containers;
    private ContainerInstancesService mock;
    private ContainerInstancesService real;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.hostname()).thenReturn(Optional.empty());
        lenient().when(config.services().containerinstances().mock()).thenReturn(false);
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        network = mock(NetworkReferences.class);
        lenient().when(network.attachVnic(any())).thenReturn(VNIC);
        manager = mock(ContainerInstanceManager.class);
        lenient().when(manager.containerName(any())).thenAnswer(call ->
                "floci-oci-ci-" + ((StoredContainer) call.getArgument(0)).getId());
        lenient().when(manager.startPod(any(), anyList(), any())).thenReturn("172.18.0.9");
        instances = new InMemoryStorage<>();
        containers = new InMemoryStorage<>();
        mock = service(null);
        real = service(manager);
    }

    private ContainerInstancesService service(ContainerInstanceManager withManager) {
        return new ContainerInstancesService(instances, containers, config, null, workRequests, withManager,
                OciContext.fromConfig(config), network, UnaryOperator.identity(), ad -> ad.equalsIgnoreCase(AD), null,
                (tenancy, region, task) -> task.run(), new MutableClock(), Runnable::run);
    }

    private static Map<String, Object> details() {
        Map<String, Object> details = new HashMap<>();
        details.put("compartmentId", COMPARTMENT);
        details.put("availabilityDomain", AD);
        details.put("displayName", "web");
        details.put("shape", "CI.Standard.E4.Flex");
        details.put("shapeConfig", Map.of("ocpus", 1));
        details.put("containers", List.of(Map.of("imageUrl", "busybox:1.36",
                "command", List.of("httpd", "-f"), "environmentVariables", Map.of("MODE", "test"))));
        details.put("vnics", List.of(Map.of("subnetId", SUBNET)));
        return details;
    }

    private StoredWorkRequest workRequest(String id) {
        return workRequests.get(ContainerInstancesService.WR_SERVICE, id);
    }

    @Test
    void mockModeCreatesAnActiveInstanceWithASucceededWorkRequest() {
        ContainerInstancesService.Created created = mock.create(details());

        StoredContainerInstance instance = created.instance();
        assertEquals("ACTIVE", instance.getLifecycleState());
        assertTrue(instance.getId().startsWith("ocid1.computecontainerinstance.oc1.iad."));
        assertEquals(List.of(VNIC), instance.getVnicIds());
        StoredContainer container = mock.containersOf(instance).get(0);
        assertEquals("ACTIVE", container.getLifecycleState());
        assertEquals("web-container-1", container.getDisplayName());
        assertEquals(List.of("httpd", "-f"), container.getAttributes().get("command"));

        StoredWorkRequest wr = workRequest(created.workRequestId());
        assertEquals("SUCCEEDED", wr.getStatus());
        assertEquals("CREATE_CONTAINER_INSTANCE", wr.getOperationType());
        assertEquals("containerInstance", wr.getResources().get(0).getEntityType());
        assertEquals("CREATED", wr.getResources().get(0).getActionType());
        assertEquals(instance.getId(), wr.getResources().get(0).getIdentifier());

        ArgumentCaptor<NetworkReferences.VnicRequest> vnic = ArgumentCaptor.forClass(NetworkReferences.VnicRequest.class);
        verify(network).attachVnic(vnic.capture());
        assertEquals(SUBNET, vnic.getValue().subnetId());
        assertTrue(vnic.getValue().allocatePrivateIp(), "mock mode takes an address from the subnet");
        verifyNoInteractions(manager);
    }

    @Test
    void theShapeConfigDefaultsTheMemoryAndAddsTheComputedFields() {
        Map<String, Object> shapeConfig = mock.create(details()).instance().getAttributes().entrySet().stream()
                .filter(e -> e.getKey().equals("shapeConfig")).findFirst().map(e -> asMap(e.getValue())).orElseThrow();
        assertEquals(1.0, shapeConfig.get("ocpus"));
        assertEquals(16.0, shapeConfig.get("memoryInGBs"));
        assertEquals(1.0, shapeConfig.get("networkingBandwidthInGbps"));
        assertNotNull(shapeConfig.get("processorDescription"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void createRejectsWhatOciRejects() {
        assertRejected("MissingParameter", d -> d.remove("containers"));
        assertRejected("MissingParameter", d -> d.remove("vnics"));
        assertRejected("InvalidParameter", d -> d.put("shape", "CI.Standard.X9.Flex"));
        assertRejected("InvalidParameter", d -> d.put("shape", "CI.Standard.E5.Flex"));
        assertRejected("InvalidParameter", d -> d.put("shapeConfig", Map.of("ocpus", 65)));
        assertRejected("InvalidParameter", d -> d.put("shapeConfig", Map.of("ocpus", 2, "memoryInGBs", 1)));
        assertRejected("InvalidParameter", d -> d.put("availabilityDomain", "Floc:US-ASHBURN-AD-9"));
        assertRejected("InvalidParameter", d -> d.put("containerRestartPolicy", "SOMETIMES"));
        assertRejected("InvalidParameter", d -> d.put("vnics", List.of(Map.of("subnetId", SUBNET),
                Map.of("subnetId", SUBNET))));
        assertRejected("InvalidParameter", d -> d.put("volumes", List.of(Map.of("name", "fss",
                "volumeType", "OCI_FSS_FILE_SYSTEM"))));
        assertRejected("InvalidParameter", d -> d.put("containers", List.of(Map.of("imageUrl", "busybox",
                "volumeMounts", List.of(Map.of("volumeName", "missing", "mountPath", "/data"))))));
        assertRejected("InvalidParameter", d -> d.put("imagePullSecrets", List.of(Map.of("secretType", "OTHER",
                "registryEndpoint", "iad.ocir.io"))));
        assertEquals(0, instances.scan(k -> true).size(), "nothing is stored for a rejected create");
    }

    private void assertRejected(String code, Consumer<Map<String, Object>> change) {
        Map<String, Object> d = details();
        change.accept(d);
        OciException e = assertThrows(OciException.class, () -> mock.create(d));
        assertEquals(code, e.getCode(), e.getMessage());
    }

    @Test
    void anUnknownSubnetIsRejectedBeforeAnythingIsStored() {
        doThrow(OciException.relatedResourceNotAuthorizedOrNotFound("subnet")).when(network).requireSubnet(SUBNET);
        OciException e = assertThrows(OciException.class, () -> mock.create(details()));
        assertEquals("RelatedResourceNotAuthorizedOrNotFound", e.getCode());
        assertEquals(0, instances.scan(k -> true).size());
        verify(network, never()).attachVnic(any());
    }

    @Test
    void volumesAndPullSecretsAreReadBackWithoutCredentials() {
        Map<String, Object> d = details();
        String data = Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));
        d.put("volumes", List.of(Map.of("name", "scratch", "volumeType", "EMPTYDIR"),
                Map.of("name", "conf", "volumeType", "CONFIGFILE",
                        "configs", List.of(Map.of("fileName", "app.conf", "data", data)))));
        d.put("imagePullSecrets", List.of(Map.of("secretType", "BASIC", "registryEndpoint", "iad.ocir.io",
                "username", Base64.getEncoder().encodeToString("user".getBytes(StandardCharsets.UTF_8)),
                "password", Base64.getEncoder().encodeToString("secret".getBytes(StandardCharsets.UTF_8)))));
        StoredContainerInstance instance = mock.create(d).instance();

        List<Map<String, Object>> volumes = ContainerInstanceManager.mapList(instance.getAttributes().get("volumes"));
        assertEquals("EPHEMERAL_STORAGE", volumes.get(0).get("backingStore"));
        assertEquals("CONFIGFILE", volumes.get(1).get("volumeType"));
        assertEquals(List.of(Map.of("secretType", "BASIC", "registryEndpoint", "iad.ocir.io")),
                instance.getAttributes().get("imagePullSecrets"));
        assertEquals(List.of(new StoredContainerInstance.PullCredential("iad.ocir.io", "user", "secret")),
                instance.getPullCredentials());
    }

    @Test
    void realModeStartsThePodAndRecordsItsAddressOnTheVnic() {
        ContainerInstancesService.Created created = real.create(details());

        StoredContainerInstance instance = real.get(created.instance().getId());
        assertEquals("ACTIVE", instance.getLifecycleState());
        assertEquals("ACTIVE", real.containersOf(instance).get(0).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(created.workRequestId()).getStatus());
        verify(manager).startPod(any(), anyList(), any());
        verify(network).setVnicPrivateIp(VNIC, "172.18.0.9");
        ArgumentCaptor<NetworkReferences.VnicRequest> vnic = ArgumentCaptor.forClass(NetworkReferences.VnicRequest.class);
        verify(network).attachVnic(vnic.capture());
        assertFalse(vnic.getValue().allocatePrivateIp(), "real mode takes the pod's address");
    }

    @Test
    void aRequestedPrivateIpIsKeptInRealMode() {
        Map<String, Object> d = details();
        d.put("vnics", List.of(Map.of("subnetId", SUBNET, "privateIp", "10.0.0.20")));
        real.create(d);
        verify(network, never()).setVnicPrivateIp(any(), any());
    }

    @Test
    void aPodThatCannotStartFailsTheInstanceAndItsWorkRequest() {
        when(manager.startPod(any(), anyList(), any())).thenThrow(new IllegalStateException("pull failed: nope"));

        ContainerInstancesService.Created created = real.create(details());

        StoredContainerInstance instance = real.get(created.instance().getId());
        assertEquals("FAILED", instance.getLifecycleState());
        assertEquals("pull failed: nope", instance.getLifecycleDetails());
        StoredWorkRequest wr = workRequest(created.workRequestId());
        assertEquals("FAILED", wr.getStatus());
        assertEquals("InternalServerError", wr.getErrors().get(0).getCode());
    }

    @Test
    void stopStartAndDeleteGoThroughTheManager() {
        String id = real.create(details()).instance().getId();

        String stop = real.stop(id, null);
        assertEquals("INACTIVE", real.get(id).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(stop).getStatus());
        verify(manager).stopPod(any(), anyList());

        when(manager.restartPod(any(), anyList(), any())).thenReturn("172.18.0.10");
        real.start(id, null);
        assertEquals("ACTIVE", real.get(id).getLifecycleState());
        verify(network).setVnicPrivateIp(VNIC, "172.18.0.10");

        String delete = real.delete(id, null);
        assertEquals("DELETED", real.get(id).getLifecycleState());
        assertEquals("DELETED", real.containersOf(real.get(id)).get(0).getLifecycleState());
        assertEquals("SUCCEEDED", workRequest(delete).getStatus());
        assertEquals("DELETED", workRequest(delete).getResources().get(0).getActionType());
        verify(manager).removePod(any(), anyList());
        verify(network).detachVnic(VNIC);
    }

    @Test
    void actionsInTheWrongStateAreIncorrectState() {
        String id = mock.create(details()).instance().getId();
        OciException e = assertThrows(OciException.class, () -> mock.start(id, null));
        assertEquals("IncorrectState", e.getCode());
        assertEquals(409, e.getHttpStatus());
        mock.delete(id, null);
        assertEquals("IncorrectState", assertThrows(OciException.class, () -> mock.delete(id, null)).getCode());
    }

    @Test
    void ifMatchIsChecked() {
        String id = mock.create(details()).instance().getId();
        assertEquals(412, assertThrows(OciException.class, () -> mock.update(id, Map.of("displayName", "x"), "stale"))
                .getHttpStatus());
    }

    @Test
    void anExitedContainerUnderNeverBecomesInactiveAndSoDoesTheInstance() {
        Map<String, Object> d = details();
        d.put("containerRestartPolicy", "NEVER");
        String id = real.create(d).instance().getId();
        when(manager.state(any())).thenReturn(Optional.of(
                new ContainerInstanceManager.ContainerState(false, 3, Instant.parse("2026-11-22T10:00:00Z"))));

        real.refreshActive();

        StoredContainer container = real.containersOf(real.get(id)).get(0);
        assertEquals("INACTIVE", container.getLifecycleState());
        assertEquals(3, container.getExitCode());
        assertEquals("2026-11-22T10:00:00Z", container.getTimeTerminated());
        assertEquals("INACTIVE", real.get(id).getLifecycleState());
        verify(manager, never()).restartContainer(any(), any());
    }

    @Test
    void anExitedContainerUnderAlwaysIsRestartedAndCounted() {
        String id = real.create(details()).instance().getId();
        when(manager.state(any())).thenReturn(Optional.of(new ContainerInstanceManager.ContainerState(false, 0, null)));

        real.refreshActive();

        StoredContainer container = real.containersOf(real.get(id)).get(0);
        assertEquals("ACTIVE", container.getLifecycleState());
        assertEquals(1, container.getContainerRestartAttemptCount());
        assertEquals("ACTIVE", real.get(id).getLifecycleState());
        verify(manager).restartContainer(any(), any());
    }

    @Test
    void onFailureRestartsOnlyANonZeroExit() {
        Map<String, Object> d = details();
        d.put("containerRestartPolicy", "ON_FAILURE");
        String id = real.create(d).instance().getId();
        when(manager.state(any())).thenReturn(Optional.of(new ContainerInstanceManager.ContainerState(false, 0, null)));

        real.refreshActive();

        assertEquals("INACTIVE", real.containersOf(real.get(id)).get(0).getLifecycleState());
        verify(manager, never()).restartContainer(any(), any());
    }

    @Test
    void changeCompartmentMovesTheContainersToo() {
        String id = mock.create(details()).instance().getId();
        String other = "ocid1.compartment.oc1..othercompartment";
        mock.changeCompartment(id, other, null);
        assertEquals(other, mock.get(id).getCompartmentId());
        assertEquals(other, mock.containersOf(mock.get(id)).get(0).getCompartmentId());
        assertEquals(1, mock.listContainers(other, id, null, null, null, null, null).size());
    }

    @Test
    void clearRemovesEveryPod() {
        real.create(details());
        real.clear();
        verify(manager).removePod(any(), anyList());
        assertTrue(instances.scan(k -> true).isEmpty());
        assertTrue(containers.scan(k -> true).isEmpty());
    }

    @Test
    void aDeleteThatDockerCannotFinishFailsTheInstanceAndKeepsItsVnic() {
        String id = real.create(details()).instance().getId();
        doThrow(new IllegalStateException("volume in use")).when(manager).removePod(any(), anyList());

        String delete = real.delete(id, null);

        assertEquals("FAILED", real.get(id).getLifecycleState());
        assertEquals("volume in use", real.get(id).getLifecycleDetails());
        StoredWorkRequest wr = workRequest(delete);
        assertEquals("FAILED", wr.getStatus());
        assertEquals("volume in use", wr.getErrors().get(0).getMessage());
        verify(network, never()).detachVnic(any());

        doNothing().when(manager).removePod(any(), anyList());
        assertEquals("SUCCEEDED", workRequest(real.delete(id, null)).getStatus());
        assertEquals("DELETED", real.get(id).getLifecycleState());
    }

    @Test
    void aStopThatDockerCannotFinishFailsItsWorkRequest() {
        String id = real.create(details()).instance().getId();
        doThrow(new IllegalStateException("daemon error")).when(manager).stopPod(any(), anyList());

        StoredWorkRequest wr = workRequest(real.stop(id, null));

        assertEquals("FAILED", wr.getStatus());
        assertEquals("daemon error", real.get(id).getLifecycleDetails());
    }

    @Test
    void theRestartPolicyAndRestartActionPassTheInstanceForItsShutdownTimeout() {
        Map<String, Object> d = details();
        d.put("gracefulShutdownTimeoutInSeconds", 42);
        String id = real.create(d).instance().getId();
        real.restart(id, null);
        ArgumentCaptor<StoredContainerInstance> instance = ArgumentCaptor.forClass(StoredContainerInstance.class);
        verify(manager).restartContainer(instance.capture(), any());
        assertEquals(42L, instance.getValue().getAttributes().get("gracefulShutdownTimeoutInSeconds"));
    }

    @Test
    void listsFilterAndSort() {
        mock.create(details());
        Map<String, Object> second = details();
        second.put("displayName", "api");
        mock.create(second);
        assertEquals(List.of("api", "web"), mock.list(COMPARTMENT, null, null, null, "displayName", null).stream()
                .map(StoredContainerInstance::getDisplayName).toList());
        assertEquals(1, mock.list(COMPARTMENT, null, "web", null, null, null).size());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> mock.list(COMPARTMENT, null, null, null, "name", null)).getCode());
        assertEquals("MissingParameter", assertThrows(OciException.class,
                () -> mock.list(null, null, null, null, null, null)).getCode());
        verify(network, times(2)).attachVnic(any());
        verify(network, never()).detachVnic(eq(VNIC));
    }
}
