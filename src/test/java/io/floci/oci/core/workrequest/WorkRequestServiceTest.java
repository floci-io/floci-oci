package io.floci.oci.core.workrequest;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkRequestServiceTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..workrequesttenancy";
    private static final String REGION = "us-phoenix-1";
    private static final String PARTITION = TenancyAwareStorageBackend.regionalPartition(TENANCY, REGION);

    private StorageBackend<String, StoredWorkRequest> raw;
    private WorkRequestService workRequests;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultRegion()).thenReturn("us-ashburn-1");
        raw = new InMemoryStorage<>();
        workRequests = new WorkRequestService(new TenancyAwareStorageBackend<>(raw, () -> PARTITION), config);
    }

    @Test
    void inProgressIsStartedButNotFinishedAndAlreadyListsItsResources() {
        String id = workRequests.inProgress("oke", "CLUSTER_CREATE", "ocid1.compartment.oc1..c",
                List.of(WorkRequestService.resource("cluster", "CREATED", "ocid1.cluster.oc1.iad.x", "/x")));

        StoredWorkRequest wr = workRequests.get(id);
        assertEquals("IN_PROGRESS", wr.getStatus());
        assertEquals(0.0f, wr.getPercentComplete().floatValue());
        assertNotNull(wr.getTimeStarted());
        assertNull(wr.getTimeFinished());
        assertEquals("ocid1.cluster.oc1.iad.x", wr.getResources().get(0).getIdentifier());
    }

    @Test
    void finishWritesIntoTheGivenTenancyAndRegionOutsideARequest() {
        String id = workRequests.inProgress("oke", "CLUSTER_CREATE", "ocid1.compartment.oc1..c", List.of());

        workRequests.finish(TENANCY, REGION, id, "SUCCEEDED");

        StoredWorkRequest wr = raw.get(PARTITION + "/" + id).orElseThrow();
        assertEquals("SUCCEEDED", wr.getStatus());
        assertEquals(100.0f, wr.getPercentComplete().floatValue());
        assertNotNull(wr.getTimeFinished());
        assertEquals(1, raw.keys().size(), "finish must not create a copy outside the tenancy partition");
    }

    @Test
    void finishLeavesAnAlreadyFinishedWorkRequestAlone() {
        String id = workRequests.inProgress("oke", "CLUSTER_CREATE", "ocid1.compartment.oc1..c", List.of());
        workRequests.finish(TENANCY, REGION, id, "FAILED");

        workRequests.finish(TENANCY, REGION, id, "SUCCEEDED");

        assertEquals("FAILED", workRequests.get(id).getStatus());
    }

    @Test
    void finishOfAnUnknownWorkRequestIsANoOp() {
        workRequests.finish(TENANCY, REGION, "ocid1.coreservicesworkrequest.oc1..missing", "SUCCEEDED");

        assertEquals(0, raw.keys().size());
    }

    @Test
    void finishInAnotherRegionLeavesTheWorkRequestInProgress() {
        String id = workRequests.inProgress("oke", "CLUSTER_CREATE", "ocid1.compartment.oc1..c", List.of());

        workRequests.finish(TENANCY, "us-ashburn-1", id, "SUCCEEDED");

        assertEquals("IN_PROGRESS", workRequests.get(id).getStatus());
        assertEquals(1, raw.keys().size());
    }
}
