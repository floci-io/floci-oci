package io.floci.oci.compat;

import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.model.BatchDeleteObjectIdentifier;
import com.oracle.bmc.objectstorage.model.BatchDeleteObjectsDetails;
import com.oracle.bmc.objectstorage.model.Bucket;
import com.oracle.bmc.objectstorage.model.CommitMultipartUploadDetails;
import com.oracle.bmc.objectstorage.model.CommitMultipartUploadPartDetails;
import com.oracle.bmc.objectstorage.model.CopyObjectDetails;
import com.oracle.bmc.objectstorage.model.CreateBucketDetails;
import com.oracle.bmc.objectstorage.model.CreateMultipartUploadDetails;
import com.oracle.bmc.objectstorage.model.DeletedObjectResult;
import com.oracle.bmc.objectstorage.model.FailedObjectResult;
import com.oracle.bmc.objectstorage.model.RenameObjectDetails;
import com.oracle.bmc.objectstorage.requests.BatchDeleteObjectsRequest;
import com.oracle.bmc.objectstorage.requests.CommitMultipartUploadRequest;
import com.oracle.bmc.objectstorage.requests.CopyObjectRequest;
import com.oracle.bmc.objectstorage.requests.CreateBucketRequest;
import com.oracle.bmc.objectstorage.requests.CreateMultipartUploadRequest;
import com.oracle.bmc.objectstorage.requests.DeleteBucketRequest;
import com.oracle.bmc.objectstorage.requests.DeleteObjectRequest;
import com.oracle.bmc.objectstorage.requests.GetBucketRequest;
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest;
import com.oracle.bmc.objectstorage.requests.GetObjectRequest;
import com.oracle.bmc.objectstorage.requests.GetWorkRequestRequest;
import com.oracle.bmc.objectstorage.requests.HeadObjectRequest;
import com.oracle.bmc.objectstorage.requests.ListObjectsRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.requests.RenameObjectRequest;
import com.oracle.bmc.objectstorage.requests.UploadPartRequest;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.UploadPartResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.floci.oci.compat.EmulatorFixture.TENANCY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** Validates the Object Storage API against the real oci-java-sdk. */
class ObjectStorageCompatibilityTest {

    private static ObjectStorageClient client;
    private static String namespace;

    @BeforeAll
    static void setUp() {
        client = EmulatorFixture.objectStorage();
        namespace = client.getNamespace(GetNamespaceRequest.builder().build()).getValue();
    }

    @AfterAll
    static void tearDown() {
        client.close();
    }

    private static String createBucket(String prefix) {
        String name = prefix + "-" + System.nanoTime();
        Bucket bucket = client.createBucket(CreateBucketRequest.builder()
                .namespaceName(namespace)
                .createBucketDetails(CreateBucketDetails.builder()
                        .name(name)
                        .compartmentId(TENANCY)
                        .build())
                .build()).getBucket();
        assertThat(bucket.getName()).isEqualTo(name);
        return name;
    }

    @Test
    void namespaceIsResolved() {
        assertThat(namespace).isEqualTo(EmulatorFixture.NAMESPACE);
    }

    @Test
    void bucketLifecycle() {
        String name = createBucket("sdk-bucket");

        Bucket fetched = client.getBucket(GetBucketRequest.builder()
                .namespaceName(namespace).bucketName(name).build()).getBucket();
        assertThat(fetched.getNamespace()).isEqualTo(namespace);
        assertThat(fetched.getEtag()).isNotBlank();

        client.deleteBucket(DeleteBucketRequest.builder()
                .namespaceName(namespace).bucketName(name).build());

        assertThatThrownBy(() -> client.getBucket(GetBucketRequest.builder()
                .namespaceName(namespace).bucketName(name).build()))
                .isInstanceOfSatisfying(BmcException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getServiceCode()).isEqualTo("BucketNotFound");
                });
    }

    @Test
    void objectPutGetHeadDelete() throws Exception {
        String bucket = createBucket("sdk-objects");
        byte[] payload = "sdk object payload".getBytes(StandardCharsets.UTF_8);

        client.putObject(PutObjectRequest.builder()
                .namespaceName(namespace).bucketName(bucket).objectName("greeting.txt")
                .contentType("text/plain")
                .opcMeta(Map.of("owner", "sdk-compat"))
                .putObjectBody(new ByteArrayInputStream(payload))
                .contentLength((long) payload.length)
                .build());

        GetObjectResponse got = client.getObject(GetObjectRequest.builder()
                .namespaceName(namespace).bucketName(bucket).objectName("greeting.txt").build());
        assertThat(got.getInputStream().readAllBytes()).isEqualTo(payload);
        assertThat(got.getETag()).isNotBlank();
        assertThat(got.getOpcMeta()).containsEntry("owner", "sdk-compat");

        var head = client.headObject(HeadObjectRequest.builder()
                .namespaceName(namespace).bucketName(bucket).objectName("greeting.txt").build());
        assertThat(head.getContentLength()).isEqualTo(payload.length);

        client.deleteObject(DeleteObjectRequest.builder()
                .namespaceName(namespace).bucketName(bucket).objectName("greeting.txt").build());
        client.deleteBucket(DeleteBucketRequest.builder()
                .namespaceName(namespace).bucketName(bucket).build());
    }

    @Test
    void listObjectsWithPrefixAndDelimiter() {
        String bucket = createBucket("sdk-list");
        for (String name : List.of("a/1.txt", "a/2.txt", "top.txt")) {
            byte[] data = "x".getBytes(StandardCharsets.UTF_8);
            client.putObject(PutObjectRequest.builder()
                    .namespaceName(namespace).bucketName(bucket).objectName(name)
                    .putObjectBody(new ByteArrayInputStream(data))
                    .contentLength((long) data.length)
                    .build());
        }

        var listing = client.listObjects(ListObjectsRequest.builder()
                .namespaceName(namespace).bucketName(bucket).delimiter("/").build())
                .getListObjects();
        assertThat(listing.getObjects()).hasSize(1);
        assertThat(listing.getPrefixes()).containsExactly("a/");

        var prefixed = client.listObjects(ListObjectsRequest.builder()
                .namespaceName(namespace).bucketName(bucket).prefix("a/").build())
                .getListObjects();
        assertThat(prefixed.getObjects()).hasSize(2);

        for (String name : List.of("a/1.txt", "a/2.txt", "top.txt")) {
            client.deleteObject(DeleteObjectRequest.builder()
                    .namespaceName(namespace).bucketName(bucket).objectName(name).build());
        }
        client.deleteBucket(DeleteBucketRequest.builder()
                .namespaceName(namespace).bucketName(bucket).build());
    }

    @Test
    void renameAndCopyWithWorkRequest() throws Exception {
        String bucket = createBucket("sdk-actions");
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        client.putObject(PutObjectRequest.builder()
                .namespaceName(namespace).bucketName(bucket).objectName("original")
                .putObjectBody(new ByteArrayInputStream(data))
                .contentLength((long) data.length)
                .build());

        client.renameObject(RenameObjectRequest.builder()
                .namespaceName(namespace).bucketName(bucket)
                .renameObjectDetails(RenameObjectDetails.builder()
                        .sourceName("original").newName("renamed").build())
                .build());

        var copyResponse = client.copyObject(CopyObjectRequest.builder()
                .namespaceName(namespace).bucketName(bucket)
                .copyObjectDetails(CopyObjectDetails.builder()
                        .sourceObjectName("renamed")
                        .destinationRegion("us-ashburn-1")
                        .destinationNamespace(namespace)
                        .destinationBucket(bucket)
                        .destinationObjectName("copied")
                        .build())
                .build());
        assertThat(copyResponse.getOpcWorkRequestId()).isNotBlank();

        var workRequest = client.getWorkRequest(GetWorkRequestRequest.builder()
                .workRequestId(copyResponse.getOpcWorkRequestId()).build()).getWorkRequest();
        assertThat(workRequest.getStatus().getValue()).isEqualTo("COMPLETED");

        GetObjectResponse copied = client.getObject(GetObjectRequest.builder()
                .namespaceName(namespace).bucketName(bucket).objectName("copied").build());
        assertThat(copied.getInputStream().readAllBytes()).isEqualTo(data);

        for (String name : List.of("renamed", "copied")) {
            client.deleteObject(DeleteObjectRequest.builder()
                    .namespaceName(namespace).bucketName(bucket).objectName(name).build());
        }
        client.deleteBucket(DeleteBucketRequest.builder()
                .namespaceName(namespace).bucketName(bucket).build());
    }

    @Test
    void batchDeleteObjectsWithPartialFailures() {
        String bucket = createBucket("sdk-batch-delete");
        for (String name : List.of("a.txt", "b.txt", "c.txt")) {
            byte[] data = "x".getBytes(StandardCharsets.UTF_8);
            client.putObject(PutObjectRequest.builder()
                    .namespaceName(namespace).bucketName(bucket).objectName(name)
                    .putObjectBody(new ByteArrayInputStream(data))
                    .contentLength((long) data.length)
                    .build());
        }

        // Mixed batch: two plain deletes succeed, a missing object and a stale ifMatch fail per-entry.
        var result = client.batchDeleteObjects(BatchDeleteObjectsRequest.builder()
                .namespaceName(namespace).bucketName(bucket)
                .batchDeleteObjectsDetails(BatchDeleteObjectsDetails.builder()
                        .objects(List.of(
                                BatchDeleteObjectIdentifier.builder().objectName("a.txt").build(),
                                BatchDeleteObjectIdentifier.builder().objectName("b.txt").build(),
                                BatchDeleteObjectIdentifier.builder().objectName("missing.txt").build(),
                                BatchDeleteObjectIdentifier.builder()
                                        .objectName("c.txt").ifMatch("stale-etag").build()))
                        .build())
                .build()).getBatchDeleteObjectsResult();

        assertThat(result.getDeleted())
                .extracting(DeletedObjectResult::getObjectName)
                .containsExactly("a.txt", "b.txt");
        assertThat(result.getDeleted().getFirst().getTimeLastModified()).isNotNull();
        assertThat(result.getFailed())
                .extracting(FailedObjectResult::getObjectName, FailedObjectResult::getStatusCode)
                .containsExactly(tuple("missing.txt", 404), tuple("c.txt", 412));

        assertThatThrownBy(() -> client.getObject(GetObjectRequest.builder()
                .namespaceName(namespace).bucketName(bucket).objectName("a.txt").build()))
                .isInstanceOfSatisfying(BmcException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(404));

        // isSkipDeletedResult suppresses the per-object success details.
        var skipped = client.batchDeleteObjects(BatchDeleteObjectsRequest.builder()
                .namespaceName(namespace).bucketName(bucket)
                .batchDeleteObjectsDetails(BatchDeleteObjectsDetails.builder()
                        .isSkipDeletedResult(true)
                        .objects(List.of(BatchDeleteObjectIdentifier.builder()
                                .objectName("c.txt").build()))
                        .build())
                .build()).getBatchDeleteObjectsResult();
        assertThat(skipped.getDeleted()).isEmpty();

        client.deleteBucket(DeleteBucketRequest.builder()
                .namespaceName(namespace).bucketName(bucket).build());
    }

    @Test
    void multipartUploadRoundtrip() throws Exception {
        String bucket = createBucket("sdk-multipart");

        var upload = client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .namespaceName(namespace).bucketName(bucket)
                .createMultipartUploadDetails(CreateMultipartUploadDetails.builder()
                        .object("assembled.bin").build())
                .build()).getMultipartUpload();

        byte[] part1 = "hello ".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "multipart".getBytes(StandardCharsets.UTF_8);
        UploadPartResponse up1 = client.uploadPart(UploadPartRequest.builder()
                .namespaceName(namespace).bucketName(bucket).objectName("assembled.bin")
                .uploadId(upload.getUploadId()).uploadPartNum(1)
                .uploadPartBody(new ByteArrayInputStream(part1))
                .contentLength((long) part1.length)
                .build());
        UploadPartResponse up2 = client.uploadPart(UploadPartRequest.builder()
                .namespaceName(namespace).bucketName(bucket).objectName("assembled.bin")
                .uploadId(upload.getUploadId()).uploadPartNum(2)
                .uploadPartBody(new ByteArrayInputStream(part2))
                .contentLength((long) part2.length)
                .build());

        client.commitMultipartUpload(CommitMultipartUploadRequest.builder()
                .namespaceName(namespace).bucketName(bucket).objectName("assembled.bin")
                .uploadId(upload.getUploadId())
                .commitMultipartUploadDetails(CommitMultipartUploadDetails.builder()
                        .partsToCommit(List.of(
                                CommitMultipartUploadPartDetails.builder()
                                        .partNum(1).etag(up1.getETag()).build(),
                                CommitMultipartUploadPartDetails.builder()
                                        .partNum(2).etag(up2.getETag()).build()))
                        .build())
                .build());

        GetObjectResponse assembled = client.getObject(GetObjectRequest.builder()
                .namespaceName(namespace).bucketName(bucket).objectName("assembled.bin").build());
        assertThat(new String(assembled.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("hello multipart");

        client.deleteObject(DeleteObjectRequest.builder()
                .namespaceName(namespace).bucketName(bucket).objectName("assembled.bin").build());
        client.deleteBucket(DeleteBucketRequest.builder()
                .namespaceName(namespace).bucketName(bucket).build());
    }
}
