package io.floci.oci.compat;

import com.oracle.bmc.containerinstances.ContainerInstanceClient;
import com.oracle.bmc.containerinstances.model.ContainerInstance;
import com.oracle.bmc.containerinstances.model.CreateContainerDetails;
import com.oracle.bmc.containerinstances.model.CreateContainerEmptyDirVolumeDetails;
import com.oracle.bmc.containerinstances.model.CreateContainerInstanceDetails;
import com.oracle.bmc.containerinstances.model.CreateContainerInstanceShapeConfigDetails;
import com.oracle.bmc.containerinstances.model.CreateContainerVnicDetails;
import com.oracle.bmc.containerinstances.model.CreateVolumeMountDetails;
import com.oracle.bmc.containerinstances.model.OperationStatus;
import com.oracle.bmc.containerinstances.model.WorkRequest;
import com.oracle.bmc.containerinstances.requests.CreateContainerInstanceRequest;
import com.oracle.bmc.containerinstances.requests.DeleteContainerInstanceRequest;
import com.oracle.bmc.containerinstances.requests.GetContainerInstanceRequest;
import com.oracle.bmc.containerinstances.requests.GetContainerRequest;
import com.oracle.bmc.containerinstances.requests.GetWorkRequestRequest;
import com.oracle.bmc.containerinstances.requests.ListContainerInstanceShapesRequest;
import com.oracle.bmc.containerinstances.requests.ListContainerInstancesRequest;
import com.oracle.bmc.containerinstances.requests.RetrieveLogsRequest;
import com.oracle.bmc.containerinstances.responses.CreateContainerInstanceResponse;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.Vnic;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;

/** Validates Container Instances against the real oci-java-sdk: create, read back, logs, VNIC, delete. */
class ContainerInstancesCompatibilityTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    private static ContainerInstanceClient containerInstances;

    @BeforeAll
    static void setUp() {
        containerInstances = EmulatorFixture.containerInstances();
    }

    @AfterAll
    static void tearDown() {
        containerInstances.close();
    }

    @Test
    void shapesAreAnItemCollection() {
        var shapes = containerInstances.listContainerInstanceShapes(ListContainerInstanceShapesRequest.builder()
                .compartmentId(TENANCY).build());
        assertThat(shapes.getContainerInstanceShapeCollection().getItems())
                .extracting(shape -> shape.getName()).contains("CI.Standard.E4.Flex", "CI.Standard.A1.Flex");
    }

    @Test
    void containerInstanceLifecycle() throws Exception {
        String availabilityDomain;
        try (IdentityClient identity = EmulatorFixture.identity()) {
            availabilityDomain = identity.listAvailabilityDomains(ListAvailabilityDomainsRequest.builder()
                    .compartmentId(TENANCY).build()).getItems().get(0).getName();
        }
        String subnetId = CompatNetworks.create(TENANCY, "java-ci").subnetId();
        CreateContainerInstanceResponse created = containerInstances.createContainerInstance(
                CreateContainerInstanceRequest.builder()
                        .createContainerInstanceDetails(CreateContainerInstanceDetails.builder()
                                .compartmentId(TENANCY)
                                .availabilityDomain(availabilityDomain)
                                .displayName("java-sdk-ci")
                                .shape("CI.Standard.E4.Flex")
                                .shapeConfig(CreateContainerInstanceShapeConfigDetails.builder()
                                        .ocpus(1f).memoryInGBs(2f).build())
                                .containers(List.of(CreateContainerDetails.builder()
                                        .imageUrl("busybox:1.36")
                                        .displayName("java-web")
                                        .command(List.of("sh", "-c"))
                                        .arguments(List.of("echo hello-from-java; exec httpd -f -p 8080"))
                                        .environmentVariables(Map.of("MODE", "java"))
                                        .volumeMounts(List.of(CreateVolumeMountDetails.builder()
                                                .volumeName("scratch").mountPath("/data").build()))
                                        .build()))
                                .volumes(List.of(CreateContainerEmptyDirVolumeDetails.builder().name("scratch").build()))
                                .vnics(List.of(CreateContainerVnicDetails.builder().subnetId(subnetId).build()))
                                .build())
                        .build());
        String id = created.getContainerInstance().getId();
        try {
            assertThat(created.getOpcWorkRequestId()).isNotBlank();
            assertThat(created.getEtag()).isNotBlank();
            assertThat(created.getContainerInstance().getContainerCount()).isEqualTo(1);

            WorkRequest workRequest = awaitWorkRequest(created.getOpcWorkRequestId());
            assertThat(workRequest.getStatus()).isEqualTo(OperationStatus.Succeeded);
            assertThat(workRequest.getResources().get(0).getIdentifier()).isEqualTo(id);

            ContainerInstance active = containerInstances.getContainerInstance(GetContainerInstanceRequest.builder()
                    .containerInstanceId(id).build()).getContainerInstance();
            assertThat(active.getLifecycleState()).isEqualTo(ContainerInstance.LifecycleState.Active);
            assertThat(active.getShapeConfig().getMemoryInGBs()).isEqualTo(2f);
            assertThat(containerInstances.listContainerInstances(ListContainerInstancesRequest.builder()
                    .compartmentId(TENANCY).build()).getContainerInstanceCollection().getItems())
                    .extracting(summary -> summary.getId()).contains(id);

            String containerId = active.getContainers().get(0).getContainerId();
            var container = containerInstances.getContainer(GetContainerRequest.builder()
                    .containerId(containerId).build()).getContainer();
            assertThat(container.getArguments()).containsExactly("echo hello-from-java; exec httpd -f -p 8080");
            assertThat(container.getEnvironmentVariables()).containsEntry("MODE", "java");

            String logs = "";
            Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
            while (!logs.contains("hello-from-java") && Instant.now().isBefore(deadline)) {
                try (InputStream stream = containerInstances.retrieveLogs(RetrieveLogsRequest.builder()
                        .containerId(containerId).build()).getInputStream()) {
                    logs = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
                Thread.sleep(500);
            }
            assertThat(logs).contains("hello-from-java");

            try (VirtualNetworkClient network = EmulatorFixture.virtualNetwork()) {
                Vnic vnic = network.getVnic(GetVnicRequest.builder()
                        .vnicId(active.getVnics().get(0).getVnicId()).build()).getVnic();
                assertThat(vnic.getSubnetId()).isEqualTo(subnetId);
                assertThat(vnic.getPrivateIp()).isNotBlank();
                assertThat(vnic.getLifecycleState()).isEqualTo(Vnic.LifecycleState.Available);
            }
        } finally {
            String deleteRequest = containerInstances.deleteContainerInstance(DeleteContainerInstanceRequest.builder()
                    .containerInstanceId(id).build()).getOpcWorkRequestId();
            awaitWorkRequest(deleteRequest);
        }
        assertThat(containerInstances.getContainerInstance(GetContainerInstanceRequest.builder()
                .containerInstanceId(id).build()).getContainerInstance().getLifecycleState())
                .isEqualTo(ContainerInstance.LifecycleState.Deleted);
    }

    private static WorkRequest awaitWorkRequest(String workRequestId) throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (true) {
            WorkRequest workRequest = containerInstances.getWorkRequest(GetWorkRequestRequest.builder()
                    .workRequestId(workRequestId).build()).getWorkRequest();
            boolean running = workRequest.getStatus() == OperationStatus.Accepted
                    || workRequest.getStatus() == OperationStatus.InProgress;
            if (!running || Instant.now().isAfter(deadline)) {
                return workRequest;
            }
            Thread.sleep(1000);
        }
    }
}
