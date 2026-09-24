package sdktestgo

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"os"
	"strings"
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

// throwawayKey returns a PEM RSA key: the SDK signs every request, so the key must parse,
// but the emulator never verifies the signature.
func throwawayKey(t *testing.T) string {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	require.NoError(t, err)
	return string(pem.EncodeToMemory(&pem.Block{
		Type:  "RSA PRIVATE KEY",
		Bytes: x509.MarshalPKCS1PrivateKey(key),
	}))
}

func getClient(t *testing.T) containerengine.ContainerEngineClient {
	provider := common.NewRawConfigurationProvider("ocid1.tenancy.oc1..test", "ocid1.user.oc1..test",
		"us-ashburn-1", "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99", throwawayKey(t), nil)
	client, err := containerengine.NewContainerEngineClientWithConfigurationProvider(provider)
	require.NoError(t, err)
	client.Host = getEndpoint()
	return client
}

// clusterIdFromWorkRequest reads the new cluster's OCID the way the Terraform provider does:
// CreateCluster returns only opc-work-request-id, and the work request's resources carry it.
func clusterIdFromWorkRequest(t *testing.T, client containerengine.ContainerEngineClient,
	workRequestId *string) string {
	wr, err := client.GetWorkRequest(context.Background(), containerengine.GetWorkRequestRequest{
		WorkRequestId: workRequestId,
	})
	require.NoError(t, err)
	for _, res := range wr.Resources {
		if strings.Contains(strings.ToLower(*res.EntityType), "cluster") &&
			res.ActionType == containerengine.WorkRequestResourceActionTypeCreated {
			return *res.Identifier
		}
	}
	t.Fatalf("work request %s has no CREATED cluster resource", *workRequestId)
	return ""
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
	require.NotNil(t, createResp.OpcWorkRequestId)
	clusterId := clusterIdFromWorkRequest(t, client, createResp.OpcWorkRequestId)

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
