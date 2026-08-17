package sdktestgo

import (
	"context"
	"os"
	"testing"

	"github.com/oracle/oci-go-sdk/v65/common"
	"github.com/oracle/oci-go-sdk/v65/containerengine"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func getEndpoint() string {
	ep := os.Getenv("FLOCI_OCI_ENDPOINT")
	if ep == "" {
		ep = "http://localhost:4599"
	}
	return ep
}

func getClient(t *testing.T) containerengine.ContainerEngineClient {
	provider := common.NewRawConfigurationProvider("ocid1.tenancy.oc1..test", "ocid1.user.oc1..test", "us-ashburn-1", "fingerprint", "privateKey", nil)
	client, err := containerengine.NewContainerEngineClientWithConfigurationProvider(provider)
	require.NoError(t, err)
	client.Host = getEndpoint()
	return client
}

func TestOkeClusterLifecycle(t *testing.T) {
	client := getClient(t)
	ctx := context.Background()

	compartmentId := "ocid1.compartment.oc1..testcompartment"
	name := "go-sdk-cluster"
	vcnId := "ocid1.vcn.oc1.iad.testvcn"
	version := "v1.30.1"

	// 1. Create Cluster
	createResp, err := client.CreateCluster(ctx, containerengine.CreateClusterRequest{
		CreateClusterDetails: containerengine.CreateClusterDetails{
			CompartmentId:     common.String(compartmentId),
			Name:              common.String(name),
			VcnId:             common.String(vcnId),
			KubernetesVersion: common.String(version),
		},
	})
	require.NoError(t, err)
	assert.NotEmpty(t, createResp.OpcWorkRequestId)
	clusterId := *createResp.Id

	// 2. Get Cluster
	getResp, err := client.GetCluster(ctx, containerengine.GetClusterRequest{
		ClusterId: common.String(clusterId),
	})
	require.NoError(t, err)
	assert.Equal(t, clusterId, *getResp.Id)
	assert.Equal(t, name, *getResp.Name)

	// 3. List Clusters
	listResp, err := client.ListClusters(ctx, containerengine.ListClustersRequest{
		CompartmentId: common.String(compartmentId),
	})
	require.NoError(t, err)
	assert.NotEmpty(t, listResp.Items)

	// 4. Create Kubeconfig
	kubeResp, err := client.CreateKubeconfig(ctx, containerengine.CreateKubeconfigRequest{
		ClusterId: common.String(clusterId),
	})
	require.NoError(t, err)
	assert.NotNil(t, kubeResp.Content)

	// 5. Delete Cluster
	_, err = client.DeleteCluster(ctx, containerengine.DeleteClusterRequest{
		ClusterId: common.String(clusterId),
	})
	require.NoError(t, err)
}
