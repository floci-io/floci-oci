package sdktestgo

import (
	"context"
	"testing"

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
