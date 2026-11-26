package sdktestgo

import (
	"context"
	"testing"
	"time"

	"github.com/oracle/oci-go-sdk/v65/common"
	"github.com/oracle/oci-go-sdk/v65/streaming"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestStreamLifecycleAndCursorConsumption(t *testing.T) {
	admin, err := streaming.NewStreamAdminClientWithConfigurationProvider(configProvider(t))
	require.NoError(t, err)
	admin.Host = getEndpoint()
	ctx := context.Background()

	created, err := admin.CreateStream(ctx, streaming.CreateStreamRequest{
		CreateStreamDetails: streaming.CreateStreamDetails{
			Name:          common.String(unique("go-stream")),
			Partitions:    common.Int(1),
			CompartmentId: common.String(tenancy()),
		},
	})
	require.NoError(t, err)
	t.Cleanup(func() {
		_, _ = admin.DeleteStream(context.Background(), streaming.DeleteStreamRequest{
			StreamId: created.Id, RequestMetadata: noRetry()})
	})
	// Dual-mode: a full body and a work-request id.
	assert.NotNil(t, created.OpcWorkRequestId)
	assert.Equal(t, "ACTIVE", string(created.LifecycleState))
	assert.Equal(t, 24, *created.RetentionInHours)
	require.NotEmpty(t, *created.MessagesEndpoint)

	data, err := streaming.NewStreamClientWithConfigurationProvider(configProvider(t), *created.MessagesEndpoint)
	require.NoError(t, err)

	// The SDK base64-encodes key and value bytes on the wire and decodes them on read.
	put, err := data.PutMessages(ctx, streaming.PutMessagesRequest{
		StreamId: created.Id,
		PutMessagesDetails: streaming.PutMessagesDetails{
			Messages: []streaming.PutMessagesDetailsEntry{
				{Key: []byte("k1"), Value: []byte("first")},
				{Value: []byte("second")},
			},
		},
	})
	require.NoError(t, err)
	assert.Equal(t, 0, *put.Failures)
	assert.Len(t, put.Entries, 2)

	cursor, err := data.CreateCursor(ctx, streaming.CreateCursorRequest{
		StreamId: created.Id,
		CreateCursorDetails: streaming.CreateCursorDetails{
			Partition: common.String("0"),
			Type:      streaming.CreateCursorDetailsTypeTrimHorizon,
		},
	})
	require.NoError(t, err)
	require.NotEmpty(t, *cursor.Value)

	messages, err := data.GetMessages(ctx, streaming.GetMessagesRequest{
		StreamId: created.Id,
		Cursor:   cursor.Value,
		Limit:    common.Int(10),
	})
	require.NoError(t, err)
	require.Len(t, messages.Items, 2)
	// Message.stream carries the stream NAME, not the OCID.
	assert.Equal(t, *created.Name, *messages.Items[0].Stream)
	assert.Equal(t, []byte("k1"), messages.Items[0].Key)
	assert.Equal(t, []byte("first"), messages.Items[0].Value)
	assert.NotEmpty(t, *messages.OpcNextCursor)
}

// awaitStreamingWorkRequest polls until the work request carries timeFinished: with the compose
// emulator a pool starts a Redpanda broker before its work request finishes.
func awaitStreamingWorkRequest(t *testing.T, admin streaming.StreamAdminClient, id *string) streaming.WorkRequest {
	t.Helper()
	deadline := time.Now().Add(180 * time.Second)
	for {
		wr, err := admin.GetWorkRequest(context.Background(), streaming.GetWorkRequestRequest{WorkRequestId: id})
		require.NoError(t, err)
		if wr.TimeFinished != nil || time.Now().After(deadline) {
			return wr.WorkRequest
		}
		time.Sleep(time.Second)
	}
}

func TestStreamPoolCrud(t *testing.T) {
	admin, err := streaming.NewStreamAdminClientWithConfigurationProvider(configProvider(t))
	require.NoError(t, err)
	admin.Host = getEndpoint()
	ctx := context.Background()
	name := unique("go-pool")

	created, err := admin.CreateStreamPool(ctx, streaming.CreateStreamPoolRequest{
		CreateStreamPoolDetails: streaming.CreateStreamPoolDetails{
			Name:          common.String(name),
			CompartmentId: common.String(tenancy()),
			KafkaSettings: &streaming.KafkaSettings{NumPartitions: common.Int(2)},
		},
	})
	require.NoError(t, err)
	require.NotNil(t, created.OpcWorkRequestId)
	require.NotNil(t, created.Etag)
	// kafkaSettings and customEncryptionKey are mandatory on StreamPool.
	require.NotNil(t, created.KafkaSettings)
	require.NotNil(t, created.CustomEncryptionKey)
	poolID := created.Id

	wr := awaitStreamingWorkRequest(t, admin, created.OpcWorkRequestId)
	assert.Equal(t, streaming.OperationStatusSucceeded, wr.Status)
	require.NotEmpty(t, wr.Resources)
	assert.Equal(t, "stream_pool", *wr.Resources[0].EntityType)
	assert.Equal(t, *poolID, *wr.Resources[0].Identifier)

	got, err := admin.GetStreamPool(ctx, streaming.GetStreamPoolRequest{StreamPoolId: poolID})
	require.NoError(t, err)
	assert.Equal(t, streaming.StreamPoolLifecycleStateActive, got.LifecycleState)
	assert.Equal(t, 2, *got.KafkaSettings.NumPartitions)
	assert.Equal(t, streaming.CustomEncryptionKeyKeyStateNone, got.CustomEncryptionKey.KeyState)

	// ListStreamPools is a bare array.
	listed, err := admin.ListStreamPools(ctx, streaming.ListStreamPoolsRequest{
		CompartmentId: common.String(tenancy()),
		Name:          common.String(name),
	})
	require.NoError(t, err)
	require.Len(t, listed.Items, 1)
	assert.Equal(t, *poolID, *listed.Items[0].Id)

	updated, err := admin.UpdateStreamPool(ctx, streaming.UpdateStreamPoolRequest{
		StreamPoolId: poolID,
		UpdateStreamPoolDetails: streaming.UpdateStreamPoolDetails{
			Name: common.String(name + "-renamed"),
		},
	})
	require.NoError(t, err)
	assert.Equal(t, name+"-renamed", *updated.Name)
	awaitStreamingWorkRequest(t, admin, updated.OpcWorkRequestId)

	moved, err := admin.ChangeStreamPoolCompartment(ctx, streaming.ChangeStreamPoolCompartmentRequest{
		StreamPoolId: poolID,
		ChangeStreamPoolCompartmentDetails: streaming.ChangeStreamPoolCompartmentDetails{
			CompartmentId: common.String(tenancy()),
		},
	})
	require.NoError(t, err)
	assert.NotNil(t, moved.OpcWorkRequestId)

	deleted, err := admin.DeleteStreamPool(ctx, streaming.DeleteStreamPoolRequest{StreamPoolId: poolID})
	require.NoError(t, err)
	assert.Equal(t, streaming.OperationStatusSucceeded,
		awaitStreamingWorkRequest(t, admin, deleted.OpcWorkRequestId).Status)

	_, err = admin.GetStreamPool(ctx, streaming.GetStreamPoolRequest{StreamPoolId: poolID, RequestMetadata: noRetry()})
	require.Error(t, err)
}
