package sdktestgo

import (
	"context"
	"testing"

	"github.com/oracle/oci-go-sdk/v65/common"
	"github.com/oracle/oci-go-sdk/v65/core"
	"github.com/stretchr/testify/require"
)

// testNetwork creates a VCN and a subnet in the tenancy: OKE, Functions and PostgreSQL reject
// network OCIDs that do not exist. It returns their OCIDs. Both are deleted when the test ends,
// the subnet first; cleanups run last-in first-out, so after the resources that use them.
func testNetwork(t *testing.T, name string) (vcnId string, subnetId string) {
	client, err := core.NewVirtualNetworkClientWithConfigurationProvider(configProvider(t))
	require.NoError(t, err)
	client.Host = getEndpoint()
	ctx := context.Background()

	vcn, err := client.CreateVcn(ctx, core.CreateVcnRequest{
		CreateVcnDetails: core.CreateVcnDetails{
			CompartmentId: common.String(tenancy()),
			DisplayName:   common.String(name + "-vcn"),
			CidrBlocks:    []string{"10.0.0.0/16"},
		},
	})
	require.NoError(t, err)
	t.Cleanup(func() {
		_, err := client.DeleteVcn(ctx, core.DeleteVcnRequest{VcnId: vcn.Id})
		require.NoError(t, err)
	})
	subnet, err := client.CreateSubnet(ctx, core.CreateSubnetRequest{
		CreateSubnetDetails: core.CreateSubnetDetails{
			CompartmentId: common.String(tenancy()),
			VcnId:         vcn.Id,
			DisplayName:   common.String(name + "-subnet"),
			CidrBlock:     common.String("10.0.0.0/24"),
		},
	})
	require.NoError(t, err)
	t.Cleanup(func() {
		_, err := client.DeleteSubnet(ctx, core.DeleteSubnetRequest{SubnetId: subnet.Id})
		require.NoError(t, err)
	})
	return *vcn.Id, *subnet.Id
}
