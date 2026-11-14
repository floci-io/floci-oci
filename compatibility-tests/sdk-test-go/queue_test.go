package sdktestgo

import (
	"context"
	"testing"

	"github.com/oracle/oci-go-sdk/v65/common"
	"github.com/oracle/oci-go-sdk/v65/queue"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func queueClients(t *testing.T) (queue.QueueAdminClient, queue.QueueClient) {
	admin, err := queue.NewQueueAdminClientWithConfigurationProvider(configProvider(t))
	require.NoError(t, err)
	admin.Host = getEndpoint()
	data, err := queue.NewQueueClientWithConfigurationProvider(configProvider(t))
	require.NoError(t, err)
	data.Host = getEndpoint()
	return admin, data
}

// createQueue is work-request driven: no body, only opc-work-request-id, whose resources carry
// the new queue's OCID. The queue is deleted when the test ends.
func createQueue(t *testing.T, admin queue.QueueAdminClient, name string) string {
	ctx := context.Background()
	created, err := admin.CreateQueue(ctx, queue.CreateQueueRequest{
		CreateQueueDetails: queue.CreateQueueDetails{
			DisplayName:   common.String(name),
			CompartmentId: common.String(tenancy()),
		},
	})
	require.NoError(t, err)
	require.NotNil(t, created.OpcWorkRequestId)

	workRequest, err := admin.GetWorkRequest(ctx, queue.GetWorkRequestRequest{WorkRequestId: created.OpcWorkRequestId})
	require.NoError(t, err)
	assert.Equal(t, "SUCCEEDED", string(workRequest.Status))
	assert.NotNil(t, workRequest.TimeFinished)
	require.NotEmpty(t, workRequest.Resources)
	queueId := workRequest.Resources[0].Identifier
	t.Cleanup(func() {
		_, _ = admin.DeleteQueue(context.Background(), queue.DeleteQueueRequest{
			QueueId: queueId, RequestMetadata: noRetry()})
	})
	return *queueId
}

func TestQueueLifecycle(t *testing.T) {
	admin, _ := queueClients(t)
	ctx := context.Background()
	queueId := createQueue(t, admin, unique("go-queue"))

	fetched, err := admin.GetQueue(ctx, queue.GetQueueRequest{QueueId: common.String(queueId)})
	require.NoError(t, err)
	assert.Equal(t, "ACTIVE", string(fetched.LifecycleState))
	assert.NotEmpty(t, *fetched.MessagesEndpoint)
	assert.Equal(t, 86400, *fetched.RetentionInSeconds)

	listed, err := admin.ListQueues(ctx, queue.ListQueuesRequest{CompartmentId: common.String(tenancy())})
	require.NoError(t, err)
	ids := make([]string, 0, len(listed.Items))
	for _, item := range listed.Items {
		ids = append(ids, *item.Id)
	}
	assert.Contains(t, ids, queueId)

	deleted, err := admin.DeleteQueue(ctx, queue.DeleteQueueRequest{QueueId: common.String(queueId)})
	require.NoError(t, err)
	assert.NotNil(t, deleted.OpcWorkRequestId)
}

func TestQueueMessageRoundtrip(t *testing.T) {
	admin, data := queueClients(t)
	ctx := context.Background()
	queueId := createQueue(t, admin, unique("go-messages"))

	put, err := data.PutMessages(ctx, queue.PutMessagesRequest{
		QueueId: common.String(queueId),
		PutMessagesDetails: queue.PutMessagesDetails{
			Messages: []queue.PutMessagesDetailsEntry{
				{Content: common.String("go one")},
				{Content: common.String("go two")},
			},
		},
	})
	require.NoError(t, err)
	require.Len(t, put.Messages, 2)
	assert.Equal(t, int64(1), *put.Messages[0].Id)

	got, err := data.GetMessages(ctx, queue.GetMessagesRequest{
		QueueId:          common.String(queueId),
		Limit:            common.Int(10),
		TimeoutInSeconds: common.Int(0),
	})
	require.NoError(t, err)
	require.Len(t, got.Messages, 2)
	first := got.Messages[0]
	assert.Equal(t, "go one", *first.Content)
	assert.NotEmpty(t, *first.Receipt)
	assert.Equal(t, 1, *first.DeliveryCount)

	stats, err := data.GetStats(ctx, queue.GetStatsRequest{QueueId: common.String(queueId)})
	require.NoError(t, err)
	assert.Equal(t, int64(2), *stats.Queue.InFlightMessages)

	_, err = data.DeleteMessage(ctx, queue.DeleteMessageRequest{
		QueueId:        common.String(queueId),
		MessageReceipt: first.Receipt,
	})
	require.NoError(t, err)
}
