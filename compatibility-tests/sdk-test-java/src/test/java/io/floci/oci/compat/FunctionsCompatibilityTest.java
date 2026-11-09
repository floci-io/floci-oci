package io.floci.oci.compat;

import com.oracle.bmc.functions.FunctionsManagementClient;
import com.oracle.bmc.functions.WorkRequestManagementClient;
import com.oracle.bmc.functions.model.Application;
import com.oracle.bmc.functions.model.ContainerImageFunctionSourceDetails;
import com.oracle.bmc.functions.model.CreateApplicationDetails;
import com.oracle.bmc.functions.model.CreateContainerImageFunctionSourceDetails;
import com.oracle.bmc.functions.model.CreateFunctionDetails;
import com.oracle.bmc.functions.model.Function;
import com.oracle.bmc.functions.model.FunctionSummary;
import com.oracle.bmc.functions.model.OperationStatus;
import com.oracle.bmc.functions.model.UpdateFunctionDetails;
import com.oracle.bmc.functions.model.WorkRequest;
import com.oracle.bmc.functions.requests.CreateApplicationRequest;
import com.oracle.bmc.functions.requests.CreateFunctionRequest;
import com.oracle.bmc.functions.requests.DeleteApplicationRequest;
import com.oracle.bmc.functions.requests.DeleteFunctionRequest;
import com.oracle.bmc.functions.requests.GetFunctionRequest;
import com.oracle.bmc.functions.requests.GetWorkRequestRequest;
import com.oracle.bmc.functions.requests.ListFunctionsRequest;
import com.oracle.bmc.functions.requests.UpdateFunctionRequest;
import com.oracle.bmc.functions.responses.CreateFunctionResponse;
import com.oracle.bmc.functions.responses.UpdateFunctionResponse;
import com.oracle.bmc.model.BmcException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Functions management plane through oci-java-sdk; invocation lives in FunctionsDockerTest. */
class FunctionsCompatibilityTest {

    private static FunctionsManagementClient functions;
    private static WorkRequestManagementClient workRequests;

    @BeforeAll
    static void setUp() {
        functions = EmulatorFixture.functionsManagement();
        workRequests = EmulatorFixture.functionsWorkRequests();
    }

    @AfterAll
    static void tearDown() {
        if (functions != null) {
            functions.close();
        }
        if (workRequests != null) {
            workRequests.close();
        }
    }

    @Test
    void applicationAndFunctionLifecycle() {
        Application app = functions.createApplication(CreateApplicationRequest.builder()
                .createApplicationDetails(CreateApplicationDetails.builder()
                        .compartmentId(TENANCY)
                        .displayName("java-app-" + System.nanoTime())
                        .subnetIds(List.of("ocid1.subnet.oc1.iad.javasubnet"))
                        .build())
                .build()).getApplication();
        assertThat(app.getLifecycleState()).isEqualTo(Application.LifecycleState.Active);
        assertThat(app.getShape()).isEqualTo(Application.Shape.GenericX86);

        CreateFunctionResponse created = functions.createFunction(CreateFunctionRequest.builder()
                .createFunctionDetails(CreateFunctionDetails.builder()
                        .applicationId(app.getId())
                        .displayName("java-fn-" + System.nanoTime())
                        .sourceDetails(CreateContainerImageFunctionSourceDetails.builder()
                                .image("iad.ocir.io/tenant/java-hello:0.0.1")
                                .build())
                        .memoryInMBs(256L)
                        .build())
                .build());
        assertThat(created.getOpcWorkRequestId()).isNotBlank();
        Function fn = created.getFunction();
        assertThat(fn.getLifecycleState()).isEqualTo(Function.LifecycleState.Active);
        assertThat(fn.getMemoryInMBs()).isEqualTo(256L);
        assertThat(fn.getInvokeEndpoint()).isNotBlank();
        assertThat(fn.getSourceDetails()).isInstanceOf(ContainerImageFunctionSourceDetails.class);
        assertThat(((ContainerImageFunctionSourceDetails) fn.getSourceDetails()).getImageDigest())
                .startsWith("sha256:");

        List<FunctionSummary> listed = functions.listFunctions(
                ListFunctionsRequest.builder().applicationId(app.getId()).build()).getItems();
        assertThat(listed).extracting(FunctionSummary::getId).contains(fn.getId());

        UpdateFunctionResponse updated = functions.updateFunction(UpdateFunctionRequest.builder()
                .functionId(fn.getId())
                .updateFunctionDetails(UpdateFunctionDetails.builder().memoryInMBs(512L).build())
                .build());
        WorkRequest workRequest = workRequests.getWorkRequest(GetWorkRequestRequest.builder()
                .workRequestId(updated.getOpcWorkRequestId())
                .build()).getWorkRequest();
        assertThat(workRequest.getStatus()).isEqualTo(OperationStatus.Succeeded);
        assertThat(workRequest.getResources().get(0).getIdentifier()).isEqualTo(fn.getId());
        assertThat(functions.getFunction(GetFunctionRequest.builder().functionId(fn.getId()).build())
                .getFunction().getMemoryInMBs()).isEqualTo(512L);

        functions.deleteFunction(DeleteFunctionRequest.builder().functionId(fn.getId()).build());
        functions.deleteApplication(DeleteApplicationRequest.builder().applicationId(app.getId()).build());
    }

    @Test
    void getMissingFunctionIsNotAuthorizedOrNotFound() {
        assertThatThrownBy(() -> functions.getFunction(GetFunctionRequest.builder()
                .functionId("ocid1.fnfunc.oc1.iad.doesnotexist")
                .build()))
                .isInstanceOfSatisfying(BmcException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getServiceCode()).isEqualTo("NotAuthorizedOrNotFound");
                });
    }
}
