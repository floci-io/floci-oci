package sdktestgo

import (
	"context"
	"strings"
	"testing"

	"github.com/oracle/oci-go-sdk/v65/common"
	"github.com/oracle/oci-go-sdk/v65/functions"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// The management plane only: invocation needs the fnserver sidecar and lives in FunctionsDockerTest.
func TestFunctionsManagementPlane(t *testing.T) {
	client, err := functions.NewFunctionsManagementClientWithConfigurationProvider(configProvider(t))
	require.NoError(t, err)
	client.Host = getEndpoint()
	workRequests, err := functions.NewWorkRequestManagementClientWithConfigurationProvider(configProvider(t))
	require.NoError(t, err)
	workRequests.Host = getEndpoint()
	ctx := context.Background()

	app, err := client.CreateApplication(ctx, functions.CreateApplicationRequest{
		CreateApplicationDetails: functions.CreateApplicationDetails{
			CompartmentId: common.String(tenancy()),
			DisplayName:   common.String(unique("go-app")),
			SubnetIds:     []string{"ocid1.subnet.oc1.iad.gosubnet"},
		},
	})
	require.NoError(t, err)
	t.Cleanup(func() {
		_, _ = client.DeleteApplication(context.Background(), functions.DeleteApplicationRequest{
			ApplicationId: app.Id, RequestMetadata: noRetry()})
	})
	assert.Equal(t, "ACTIVE", string(app.LifecycleState))
	assert.Equal(t, "GENERIC_X86", string(app.Shape))

	fn, err := client.CreateFunction(ctx, functions.CreateFunctionRequest{
		CreateFunctionDetails: functions.CreateFunctionDetails{
			ApplicationId: app.Id,
			DisplayName:   common.String(unique("go-fn")),
			SourceDetails: functions.CreateContainerImageFunctionSourceDetails{
				Image: common.String("iad.ocir.io/tenant/go-hello:0.0.1"),
			},
			MemoryInMBs: common.Int64(256),
		},
	})
	require.NoError(t, err)
	t.Cleanup(func() {
		_, _ = client.DeleteFunction(context.Background(), functions.DeleteFunctionRequest{
			FunctionId: fn.Id, RequestMetadata: noRetry()})
	})
	assert.NotNil(t, fn.OpcWorkRequestId)
	assert.Equal(t, "ACTIVE", string(fn.LifecycleState))
	assert.Equal(t, int64(256), *fn.MemoryInMBs)
	assert.NotEmpty(t, *fn.InvokeEndpoint)
	source, ok := fn.SourceDetails.(functions.ContainerImageFunctionSourceDetails)
	require.Truef(t, ok, "expected CONTAINER_IMAGE source details, got %T", fn.SourceDetails)
	assert.True(t, strings.HasPrefix(*source.ImageDigest, "sha256:"))

	listed, err := client.ListFunctions(ctx, functions.ListFunctionsRequest{ApplicationId: app.Id})
	require.NoError(t, err)
	ids := make([]string, 0, len(listed.Items))
	for _, item := range listed.Items {
		ids = append(ids, *item.Id)
	}
	assert.Contains(t, ids, *fn.Id)

	updated, err := client.UpdateFunction(ctx, functions.UpdateFunctionRequest{
		FunctionId:            fn.Id,
		UpdateFunctionDetails: functions.UpdateFunctionDetails{MemoryInMBs: common.Int64(512)},
	})
	require.NoError(t, err)
	require.NotNil(t, updated.OpcWorkRequestId)
	workRequest, err := workRequests.GetWorkRequest(ctx, functions.GetWorkRequestRequest{
		WorkRequestId: updated.OpcWorkRequestId,
	})
	require.NoError(t, err)
	assert.Equal(t, "SUCCEEDED", string(workRequest.Status))
	require.NotEmpty(t, workRequest.Resources)
	assert.Equal(t, *fn.Id, *workRequest.Resources[0].Identifier)

	fetched, err := client.GetFunction(ctx, functions.GetFunctionRequest{FunctionId: fn.Id})
	require.NoError(t, err)
	assert.Equal(t, int64(512), *fetched.MemoryInMBs)

	runtimes, err := client.ListFunctionsRuntimes(ctx, functions.ListFunctionsRuntimesRequest{
		Name: common.String("python312.ol9"),
	})
	require.NoError(t, err)
	names := make([]string, 0, len(runtimes.Items))
	for _, runtime := range runtimes.Items {
		names = append(names, *runtime.Name)
	}
	assert.Equal(t, []string{"python312.ol9"}, names)
}
