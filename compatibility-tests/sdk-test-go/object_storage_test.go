package sdktestgo

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/base64"
	"io"
	"testing"

	"github.com/oracle/oci-go-sdk/v65/common"
	"github.com/oracle/oci-go-sdk/v65/objectstorage"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func objectStorageClient(t *testing.T) objectstorage.ObjectStorageClient {
	client, err := objectstorage.NewObjectStorageClientWithConfigurationProvider(configProvider(t))
	require.NoError(t, err)
	client.Host = getEndpoint()
	return client
}

func namespaceOf(t *testing.T, client objectstorage.ObjectStorageClient) string {
	resp, err := client.GetNamespace(context.Background(), objectstorage.GetNamespaceRequest{})
	require.NoError(t, err)
	require.NotNil(t, resp.Value)
	require.NotEmpty(t, *resp.Value)
	return *resp.Value
}

// tempBucket creates a bucket that is emptied and deleted when the test ends.
func tempBucket(t *testing.T, client objectstorage.ObjectStorageClient, namespace string) string {
	name := unique("go-bucket")
	_, err := client.CreateBucket(context.Background(), objectstorage.CreateBucketRequest{
		NamespaceName: common.String(namespace),
		CreateBucketDetails: objectstorage.CreateBucketDetails{
			Name:          common.String(name),
			CompartmentId: common.String(tenancy()),
		},
	})
	require.NoError(t, err)
	t.Cleanup(func() {
		ctx := context.Background()
		listing, err := client.ListObjects(ctx, objectstorage.ListObjectsRequest{
			NamespaceName: common.String(namespace),
			BucketName:    common.String(name),
		})
		if err == nil {
			for _, object := range listing.Objects {
				_, _ = client.DeleteObject(ctx, objectstorage.DeleteObjectRequest{
					NamespaceName:   common.String(namespace),
					BucketName:      common.String(name),
					ObjectName:      object.Name,
					RequestMetadata: noRetry(),
				})
			}
		}
		_, _ = client.DeleteBucket(ctx, objectstorage.DeleteBucketRequest{
			NamespaceName:   common.String(namespace),
			BucketName:      common.String(name),
			RequestMetadata: noRetry(),
		})
	})
	return name
}

func putObject(t *testing.T, client objectstorage.ObjectStorageClient, namespace, bucket, name string,
	payload []byte) objectstorage.PutObjectResponse {
	resp, err := client.PutObject(context.Background(), objectstorage.PutObjectRequest{
		NamespaceName: common.String(namespace),
		BucketName:    common.String(bucket),
		ObjectName:    common.String(name),
		ContentLength: common.Int64(int64(len(payload))),
		PutObjectBody: io.NopCloser(bytes.NewReader(payload)),
	})
	require.NoError(t, err)
	return resp
}

func getObjectBytes(t *testing.T, client objectstorage.ObjectStorageClient, namespace, bucket,
	name string) (objectstorage.GetObjectResponse, []byte) {
	resp, err := client.GetObject(context.Background(), objectstorage.GetObjectRequest{
		NamespaceName: common.String(namespace),
		BucketName:    common.String(bucket),
		ObjectName:    common.String(name),
	})
	require.NoError(t, err)
	defer resp.Content.Close()
	content, err := io.ReadAll(resp.Content)
	require.NoError(t, err)
	return resp, content
}

func TestObjectStorageBucketLifecycle(t *testing.T) {
	client := objectStorageClient(t)
	ctx := context.Background()
	namespace := namespaceOf(t, client)
	name := unique("go-bucket-crud")

	created, err := client.CreateBucket(ctx, objectstorage.CreateBucketRequest{
		NamespaceName: common.String(namespace),
		CreateBucketDetails: objectstorage.CreateBucketDetails{
			Name:          common.String(name),
			CompartmentId: common.String(tenancy()),
		},
	})
	require.NoError(t, err)
	assert.Equal(t, namespace, *created.Namespace)
	assert.NotEmpty(t, created.ETag)

	fetched, err := client.GetBucket(ctx, objectstorage.GetBucketRequest{
		NamespaceName: common.String(namespace),
		BucketName:    common.String(name),
	})
	require.NoError(t, err)
	assert.NotEmpty(t, fetched.Etag)

	_, err = client.DeleteBucket(ctx, objectstorage.DeleteBucketRequest{
		NamespaceName: common.String(namespace),
		BucketName:    common.String(name),
	})
	require.NoError(t, err)

	_, err = client.GetBucket(ctx, objectstorage.GetBucketRequest{
		NamespaceName: common.String(namespace),
		BucketName:    common.String(name),
	})
	requireServiceError(t, err, 404, "BucketNotFound")
}

func TestObjectStoragePutGetHeadDelete(t *testing.T) {
	client := objectStorageClient(t)
	ctx := context.Background()
	namespace := namespaceOf(t, client)
	bucket := tempBucket(t, client, namespace)
	payload := []byte("go sdk payload")

	put, err := client.PutObject(ctx, objectstorage.PutObjectRequest{
		NamespaceName: common.String(namespace),
		BucketName:    common.String(bucket),
		ObjectName:    common.String("greeting.txt"),
		ContentLength: common.Int64(int64(len(payload))),
		PutObjectBody: io.NopCloser(bytes.NewReader(payload)),
		ContentType:   common.String("text/plain"),
		OpcMeta:       map[string]string{"owner": "go-compat"},
	})
	require.NoError(t, err)
	sum := md5.Sum(payload)
	require.NotNil(t, put.OpcContentMd5)
	assert.Equal(t, base64.StdEncoding.EncodeToString(sum[:]), *put.OpcContentMd5)

	got, content := getObjectBytes(t, client, namespace, bucket, "greeting.txt")
	assert.Equal(t, payload, content)
	assert.Equal(t, "go-compat", got.OpcMeta["owner"])
	assert.Equal(t, "text/plain", *got.ContentType)

	head, err := client.HeadObject(ctx, objectstorage.HeadObjectRequest{
		NamespaceName: common.String(namespace),
		BucketName:    common.String(bucket),
		ObjectName:    common.String("greeting.txt"),
	})
	require.NoError(t, err)
	assert.Equal(t, int64(len(payload)), *head.ContentLength)

	_, err = client.DeleteObject(ctx, objectstorage.DeleteObjectRequest{
		NamespaceName: common.String(namespace),
		BucketName:    common.String(bucket),
		ObjectName:    common.String("greeting.txt"),
	})
	require.NoError(t, err)

	_, err = client.GetObject(ctx, objectstorage.GetObjectRequest{
		NamespaceName: common.String(namespace),
		BucketName:    common.String(bucket),
		ObjectName:    common.String("greeting.txt"),
	})
	requireServiceError(t, err, 404, "ObjectNotFound")
}

func TestObjectStorageListWithPrefixAndDelimiter(t *testing.T) {
	client := objectStorageClient(t)
	ctx := context.Background()
	namespace := namespaceOf(t, client)
	bucket := tempBucket(t, client, namespace)
	for _, name := range []string{"a/1.txt", "a/2.txt", "top.txt"} {
		putObject(t, client, namespace, bucket, name, []byte("x"))
	}

	delimited, err := client.ListObjects(ctx, objectstorage.ListObjectsRequest{
		NamespaceName: common.String(namespace),
		BucketName:    common.String(bucket),
		Delimiter:     common.String("/"),
	})
	require.NoError(t, err)
	assert.Len(t, delimited.Objects, 1)
	assert.Equal(t, []string{"a/"}, delimited.Prefixes)

	prefixed, err := client.ListObjects(ctx, objectstorage.ListObjectsRequest{
		NamespaceName: common.String(namespace),
		BucketName:    common.String(bucket),
		Prefix:        common.String("a/"),
	})
	require.NoError(t, err)
	assert.Len(t, prefixed.Objects, 2)
}

func TestObjectStorageCopyObjectViaWorkRequest(t *testing.T) {
	client := objectStorageClient(t)
	ctx := context.Background()
	namespace := namespaceOf(t, client)
	bucket := tempBucket(t, client, namespace)
	putObject(t, client, namespace, bucket, "source", []byte("copy me"))

	copied, err := client.CopyObject(ctx, objectstorage.CopyObjectRequest{
		NamespaceName: common.String(namespace),
		BucketName:    common.String(bucket),
		CopyObjectDetails: objectstorage.CopyObjectDetails{
			SourceObjectName:      common.String("source"),
			DestinationRegion:     common.String("us-ashburn-1"),
			DestinationNamespace:  common.String(namespace),
			DestinationBucket:     common.String(bucket),
			DestinationObjectName: common.String("copied"),
		},
	})
	require.NoError(t, err)
	require.NotNil(t, copied.OpcWorkRequestId)

	workRequest, err := client.GetWorkRequest(ctx, objectstorage.GetWorkRequestRequest{
		WorkRequestId: copied.OpcWorkRequestId,
	})
	require.NoError(t, err)
	assert.Equal(t, "COMPLETED", string(workRequest.Status))

	_, content := getObjectBytes(t, client, namespace, bucket, "copied")
	assert.Equal(t, []byte("copy me"), content)
}

func TestObjectStorageMultipartUpload(t *testing.T) {
	client := objectStorageClient(t)
	ctx := context.Background()
	namespace := namespaceOf(t, client)
	bucket := tempBucket(t, client, namespace)
	parts := [][]byte{[]byte("hello "), []byte("go")}

	upload, err := client.CreateMultipartUpload(ctx, objectstorage.CreateMultipartUploadRequest{
		NamespaceName: common.String(namespace),
		BucketName:    common.String(bucket),
		CreateMultipartUploadDetails: objectstorage.CreateMultipartUploadDetails{
			Object: common.String("assembled.bin"),
		},
	})
	require.NoError(t, err)

	var commits []objectstorage.CommitMultipartUploadPartDetails
	var partDigests []byte
	for i, part := range parts {
		uploaded, err := client.UploadPart(ctx, objectstorage.UploadPartRequest{
			NamespaceName:  common.String(namespace),
			BucketName:     common.String(bucket),
			ObjectName:     common.String("assembled.bin"),
			UploadId:       upload.UploadId,
			UploadPartNum:  common.Int(i + 1),
			ContentLength:  common.Int64(int64(len(part))),
			UploadPartBody: io.NopCloser(bytes.NewReader(part)),
		})
		require.NoError(t, err)
		commits = append(commits, objectstorage.CommitMultipartUploadPartDetails{
			PartNum: common.Int(i + 1),
			Etag:    uploaded.ETag,
		})
		digest := md5.Sum(part)
		partDigests = append(partDigests, digest[:]...)
	}

	committed, err := client.CommitMultipartUpload(ctx, objectstorage.CommitMultipartUploadRequest{
		NamespaceName: common.String(namespace),
		BucketName:    common.String(bucket),
		ObjectName:    common.String("assembled.bin"),
		UploadId:      upload.UploadId,
		CommitMultipartUploadDetails: objectstorage.CommitMultipartUploadDetails{
			PartsToCommit: commits,
		},
	})
	require.NoError(t, err)
	// The MD5 of the concatenated part digests, then a hyphen and the part count.
	manifest := md5.Sum(partDigests)
	expected := base64.StdEncoding.EncodeToString(manifest[:]) + "-2"
	require.NotNil(t, committed.OpcMultipartMd5)
	assert.Equal(t, expected, *committed.OpcMultipartMd5)

	assembled, content := getObjectBytes(t, client, namespace, bucket, "assembled.bin")
	assert.Equal(t, []byte("hello go"), content)
	require.NotNil(t, assembled.OpcMultipartMd5)
	assert.Equal(t, expected, *assembled.OpcMultipartMd5)
}
