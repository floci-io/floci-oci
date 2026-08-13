package io.floci.oci.compat;

import com.oracle.bmc.containerengine.ContainerEngineClient;
import com.oracle.bmc.containerengine.model.CreateClusterDetails;
import com.oracle.bmc.containerengine.model.CreateNodePoolDetails;
import com.oracle.bmc.containerengine.requests.CreateClusterRequest;
import com.oracle.bmc.containerengine.requests.CreateKubeconfigRequest;
import com.oracle.bmc.containerengine.requests.CreateNodePoolRequest;
import com.oracle.bmc.containerengine.requests.DeleteClusterRequest;
import com.oracle.bmc.containerengine.requests.DeleteNodePoolRequest;
import com.oracle.bmc.containerengine.requests.GetClusterRequest;
import com.oracle.bmc.containerengine.requests.GetNodePoolRequest;
import com.oracle.bmc.containerengine.requests.ListClustersRequest;
import com.oracle.bmc.containerengine.requests.ListNodePoolsRequest;
import com.oracle.bmc.containerengine.responses.CreateClusterResponse;
import com.oracle.bmc.containerengine.responses.CreateNodePoolResponse;
import com.oracle.bmc.containerengine.responses.GetClusterResponse;
import com.oracle.bmc.containerengine.responses.GetNodePoolResponse;
import com.oracle.bmc.containerengine.responses.ListClustersResponse;
import com.oracle.bmc.containerengine.responses.ListNodePoolsResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;

class OkeCompatibilityTest {

    private static ContainerEngineClient okeClient;

    @BeforeAll
    static void setUp() {
        okeClient = EmulatorFixture.containerEngine();
    }

    @AfterAll
    static void tearDown() {
        if (okeClient != null) {
            okeClient.close();
        }
    }

    @Test
    void testOkeClusterAndNodePoolLifecycle() {
        // 1. Create Cluster
        CreateClusterResponse createClusterResponse = okeClient.createCluster(
            CreateClusterRequest.builder()
                .createClusterDetails(CreateClusterDetails.builder()
                    .compartmentId(TENANCY)
                    .name("java-sdk-cluster")
                    .vcnId("ocid1.vcn.oc1.iad.javavcn")
                    .kubernetesVersion("v1.30.1")
                    .build())
                .build()
        );

        assertThat(createClusterResponse.getOpcWorkRequestId()).isNotNull();
        assertThat(createClusterResponse.getOpcRequestId()).isNotNull();

        // 2. List Clusters & get created cluster ID
        ListClustersResponse listClustersResponse = okeClient.listClusters(
            ListClustersRequest.builder().compartmentId(TENANCY).build()
        );
        assertThat(listClustersResponse.getItems()).isNotEmpty();
        String clusterId = listClustersResponse.getItems().get(0).getId();

        // 3. Get Cluster
        GetClusterResponse getClusterResponse = okeClient.getCluster(
            GetClusterRequest.builder().clusterId(clusterId).build()
        );
        assertThat(getClusterResponse.getCluster().getName()).isEqualTo("java-sdk-cluster");

        // 4. Create Kubeconfig
        var kubeconfigResponse = okeClient.createKubeconfig(
            CreateKubeconfigRequest.builder().clusterId(clusterId).build()
        );
        assertThat(kubeconfigResponse.getInputStream()).isNotNull();

        // 5. Create Node Pool
        CreateNodePoolResponse createNodePoolResponse = okeClient.createNodePool(
            CreateNodePoolRequest.builder()
                .createNodePoolDetails(CreateNodePoolDetails.builder()
                    .compartmentId(TENANCY)
                    .clusterId(clusterId)
                    .name("java-pool")
                    .kubernetesVersion("v1.30.1")
                    .nodeShape("VM.Standard.E4.Flex")
                    .quantityPerSubnet(2)
                    .build())
                .build()
        );
        assertThat(createNodePoolResponse.getOpcWorkRequestId()).isNotNull();

        // 6. List Node Pools & get node pool ID
        ListNodePoolsResponse listNodePoolsResponse = okeClient.listNodePools(
            ListNodePoolsRequest.builder().compartmentId(TENANCY).clusterId(clusterId).build()
        );
        assertThat(listNodePoolsResponse.getItems()).isNotEmpty();
        String nodePoolId = listNodePoolsResponse.getItems().get(0).getId();

        // 7. Get Node Pool
        GetNodePoolResponse getNodePoolResponse = okeClient.getNodePool(
            GetNodePoolRequest.builder().nodePoolId(nodePoolId).build()
        );
        assertThat(getNodePoolResponse.getNodePool().getName()).isEqualTo("java-pool");

        // 8. Delete Node Pool & Cluster
        okeClient.deleteNodePool(DeleteNodePoolRequest.builder().nodePoolId(nodePoolId).build());
        okeClient.deleteCluster(DeleteClusterRequest.builder().clusterId(clusterId).build());
    }
}
