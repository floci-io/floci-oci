package sdktestgo

import (
	"context"
	"fmt"
	"strings"
	"testing"

	"github.com/oracle/oci-go-sdk/v65/common"
	"github.com/oracle/oci-go-sdk/v65/identity"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func identityClient(t *testing.T) identity.IdentityClient {
	client, err := identity.NewIdentityClientWithConfigurationProvider(configProvider(t))
	require.NoError(t, err)
	client.Host = getEndpoint()
	return client
}

func TestIdentityCompartmentCrudAndAsyncDelete(t *testing.T) {
	client := identityClient(t)
	ctx := context.Background()
	name := unique("go-compartment")

	created, err := client.CreateCompartment(ctx, identity.CreateCompartmentRequest{
		CreateCompartmentDetails: identity.CreateCompartmentDetails{
			CompartmentId: common.String(tenancy()),
			Name:          common.String(name),
			Description:   common.String("go compat test"),
		},
	})
	require.NoError(t, err)
	assert.True(t, strings.HasPrefix(*created.Id, "ocid1.compartment."))
	assert.Equal(t, "ACTIVE", string(created.LifecycleState))
	assert.NotEmpty(t, created.Etag)
	assert.NotEmpty(t, created.OpcRequestId)

	fetched, err := client.GetCompartment(ctx, identity.GetCompartmentRequest{CompartmentId: created.Id})
	require.NoError(t, err)
	assert.Equal(t, name, *fetched.Name)

	listed, err := client.ListCompartments(ctx, identity.ListCompartmentsRequest{
		CompartmentId: common.String(tenancy()),
		Name:          common.String(name),
	})
	require.NoError(t, err)
	require.Len(t, listed.Items, 1)
	assert.Equal(t, *created.Id, *listed.Items[0].Id)

	deleted, err := client.DeleteCompartment(ctx, identity.DeleteCompartmentRequest{CompartmentId: created.Id})
	require.NoError(t, err)
	require.NotNil(t, deleted.OpcWorkRequestId)

	workRequest, err := client.GetWorkRequest(ctx, identity.GetWorkRequestRequest{
		WorkRequestId: deleted.OpcWorkRequestId,
	})
	require.NoError(t, err)
	assert.Equal(t, "SUCCEEDED", string(workRequest.Status))
	assert.Equal(t, "DELETE_COMPARTMENT", string(workRequest.OperationType))
}

func TestIdentityUserGroupMembershipRoundtrip(t *testing.T) {
	client := identityClient(t)
	ctx := context.Background()
	suffix := unique("go")

	user, err := client.CreateUser(ctx, identity.CreateUserRequest{
		CreateUserDetails: identity.CreateUserDetails{
			CompartmentId: common.String(tenancy()),
			Name:          common.String("user-" + suffix),
			Description:   common.String("go user"),
			Email:         common.String("go@example.com"),
		},
	})
	require.NoError(t, err)
	require.NotNil(t, user.IsMfaActivated)
	assert.False(t, *user.IsMfaActivated)

	group, err := client.CreateGroup(ctx, identity.CreateGroupRequest{
		CreateGroupDetails: identity.CreateGroupDetails{
			CompartmentId: common.String(tenancy()),
			Name:          common.String("group-" + suffix),
			Description:   common.String("go group"),
		},
	})
	require.NoError(t, err)

	membership, err := client.AddUserToGroup(ctx, identity.AddUserToGroupRequest{
		AddUserToGroupDetails: identity.AddUserToGroupDetails{UserId: user.Id, GroupId: group.Id},
	})
	require.NoError(t, err)
	assert.Equal(t, *user.Id, *membership.UserId)

	memberships, err := client.ListUserGroupMemberships(ctx, identity.ListUserGroupMembershipsRequest{
		CompartmentId: common.String(tenancy()),
		UserId:        user.Id,
	})
	require.NoError(t, err)
	assert.Len(t, memberships.Items, 1)

	_, err = client.RemoveUserFromGroup(ctx, identity.RemoveUserFromGroupRequest{UserGroupMembershipId: membership.Id})
	require.NoError(t, err)
	_, err = client.DeleteUser(ctx, identity.DeleteUserRequest{UserId: user.Id})
	require.NoError(t, err)
	_, err = client.DeleteGroup(ctx, identity.DeleteGroupRequest{GroupId: group.Id})
	require.NoError(t, err)

	_, err = client.GetUser(ctx, identity.GetUserRequest{UserId: user.Id, RequestMetadata: noRetry()})
	requireServiceError(t, err, 404, "NotAuthorizedOrNotFound")
}

func TestIdentityPolicyLifecycle(t *testing.T) {
	client := identityClient(t)
	ctx := context.Background()

	policy, err := client.CreatePolicy(ctx, identity.CreatePolicyRequest{
		CreatePolicyDetails: identity.CreatePolicyDetails{
			CompartmentId: common.String(tenancy()),
			Name:          common.String(unique("go-policy")),
			Description:   common.String("go policy"),
			Statements:    []string{"Allow group Administrators to manage all-resources in tenancy"},
		},
	})
	require.NoError(t, err)
	assert.Len(t, policy.Statements, 1)

	_, err = client.DeletePolicy(ctx, identity.DeletePolicyRequest{PolicyId: policy.Id})
	require.NoError(t, err)
}

func TestIdentityStaleIfMatchIs412(t *testing.T) {
	client := identityClient(t)
	ctx := context.Background()

	user, err := client.CreateUser(ctx, identity.CreateUserRequest{
		CreateUserDetails: identity.CreateUserDetails{
			CompartmentId: common.String(tenancy()),
			Name:          common.String(unique("go-etag-user")),
			Description:   common.String("u"),
		},
	})
	require.NoError(t, err)
	t.Cleanup(func() {
		_, _ = client.DeleteUser(context.Background(), identity.DeleteUserRequest{
			UserId: user.Id, RequestMetadata: noRetry()})
	})

	_, err = client.UpdateUser(ctx, identity.UpdateUserRequest{
		UserId:            user.Id,
		UpdateUserDetails: identity.UpdateUserDetails{Description: common.String("changed")},
		IfMatch:           common.String("stale-etag"),
	})
	requireServiceError(t, err, 412, "NoEtagMatch")
}

func TestIdentityReferenceData(t *testing.T) {
	client := identityClient(t)
	ctx := context.Background()

	regions, err := client.ListRegions(ctx)
	require.NoError(t, err)
	names := make([]string, 0, len(regions.Items))
	for _, region := range regions.Items {
		names = append(names, *region.Name)
	}
	assert.Contains(t, names, "us-ashburn-1")

	ads, err := client.ListAvailabilityDomains(ctx, identity.ListAvailabilityDomainsRequest{
		CompartmentId: common.String(tenancy()),
	})
	require.NoError(t, err)
	assert.Len(t, ads.Items, 3)

	tenancyResp, err := client.GetTenancy(ctx, identity.GetTenancyRequest{TenancyId: common.String(tenancy())})
	require.NoError(t, err)
	assert.Equal(t, tenancy(), *tenancyResp.Id)
}

func TestIdentityPaginationUsesOpcNextPage(t *testing.T) {
	client := identityClient(t)
	ctx := context.Background()
	marker := unique("go-page")

	for i := 0; i < 3; i++ {
		group, err := client.CreateGroup(ctx, identity.CreateGroupRequest{
			CreateGroupDetails: identity.CreateGroupDetails{
				CompartmentId: common.String(tenancy()),
				Name:          common.String(fmt.Sprintf("%s-%d", marker, i)),
				Description:   common.String("g"),
			},
		})
		require.NoError(t, err)
		groupId := group.Id
		t.Cleanup(func() {
			_, _ = client.DeleteGroup(context.Background(), identity.DeleteGroupRequest{
				GroupId: groupId, RequestMetadata: noRetry()})
		})
	}

	first, err := client.ListGroups(ctx, identity.ListGroupsRequest{
		CompartmentId: common.String(tenancy()),
		Limit:         common.Int(2),
	})
	require.NoError(t, err)
	assert.Len(t, first.Items, 2)
	require.NotNil(t, first.OpcNextPage)

	second, err := client.ListGroups(ctx, identity.ListGroupsRequest{
		CompartmentId: common.String(tenancy()),
		Limit:         common.Int(2),
		Page:          first.OpcNextPage,
	})
	require.NoError(t, err)
	assert.NotEmpty(t, second.Items)
}
