package io.floci.oci.services.objectstorage;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * The S3 Compatibility API reached the way clients reach it: path style on the
 * {@code <namespace>.compat.objectstorage.<region>} host, SigV4-signed (the signature is not checked).
 */
@QuarkusTest
class S3CompatRestIntegrationTest {

    private static final String NAMESPACE = "floci-test";
    private static final String HOST = NAMESPACE + ".compat.objectstorage.us-ashburn-1.localhost.floci.io";
    private static final String SIGV4 = "AWS4-HMAC-SHA256 Credential=%s/20261118/us-ashburn-1/s3/aws4_request, "
            + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=0000";

    private static RequestSpecification s3() {
        return s3("unknownaccesskey");
    }

    private static RequestSpecification s3(String accessKey) {
        return given().urlEncodingEnabled(false)
                .header("Host", HOST)
                .header("Authorization", String.format(SIGV4, accessKey))
                .header("x-amz-content-sha256", "UNSIGNED-PAYLOAD");
    }

    private static String bucket() {
        return "s3it-" + System.nanoTime();
    }

    private static String md5Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
    }

    @Test
    void bucketAndObjectRoundtripVisibleToTheNativeApi() throws Exception {
        String bucket = bucket();
        s3().when().put("/" + bucket).then().statusCode(200).header("Location", "/" + bucket);
        s3().when().head("/" + bucket).then().statusCode(200);

        byte[] data = "hello s3".getBytes(StandardCharsets.UTF_8);
        s3().contentType("text/plain").header("x-amz-meta-owner", "it").body(data)
            .when().put("/" + bucket + "/dir/a.txt")
            .then().statusCode(200).header("ETag", "\"" + md5Hex(data) + "\"");

        s3().when().get("/" + bucket + "/dir/a.txt")
            .then().statusCode(200)
                .header("ETag", "\"" + md5Hex(data) + "\"")
                .header("x-amz-meta-owner", "it")
                .header("Last-Modified", notNullValue())
                .body(equalTo("hello s3"));

        s3().header("Range", "bytes=0-4").when().get("/" + bucket + "/dir/a.txt")
            .then().statusCode(206).header("Content-Range", "bytes 0-4/8").body(equalTo("hello"));

        // The same object through the native Object Storage API.
        given().when().get("/n/" + NAMESPACE + "/b/" + bucket + "/o/dir/a.txt")
            .then().statusCode(200).header("opc-meta-owner", "it").body(equalTo("hello s3"));

        s3().when().get("/").then().statusCode(200)
                .contentType(containsString("xml"))
                .body(containsString("<Name>" + bucket + "</Name>"));

        s3().when().delete("/" + bucket + "/dir/a.txt").then().statusCode(204);
        s3().when().delete("/" + bucket + "/dir/a.txt").then().statusCode(204);
        s3().when().delete("/" + bucket).then().statusCode(204);
    }

    @Test
    void listObjectsV2WithDelimiterAndPagination() {
        String bucket = bucket();
        s3().when().put("/" + bucket).then().statusCode(200);
        for (String key : new String[]{"a/1", "a/2", "b", "c", "d"}) {
            s3().body(key).when().put("/" + bucket + "/" + key).then().statusCode(200);
        }

        s3().queryParam("list-type", "2").queryParam("delimiter", "/")
            .when().get("/" + bucket)
            .then().statusCode(200)
                .body("ListBucketResult.KeyCount", equalTo("4"))
                .body("ListBucketResult.CommonPrefixes.Prefix", equalTo("a/"))
                .body("ListBucketResult.Contents.Key", contains("b", "c", "d"));

        String token = s3().queryParam("list-type", "2").queryParam("max-keys", "2")
            .when().get("/" + bucket)
            .then().statusCode(200)
                .body("ListBucketResult.IsTruncated", equalTo("true"))
                .body("ListBucketResult.Contents.Key", contains("a/1", "a/2"))
                .extract().path("ListBucketResult.NextContinuationToken");

        s3().queryParam("list-type", "2").queryParam("continuation-token", token)
            .when().get("/" + bucket)
            .then().statusCode(200)
                .body("ListBucketResult.IsTruncated", equalTo("false"))
                .body("ListBucketResult.Contents.Key", contains("b", "c", "d"));

        s3().queryParam("marker", "b").when().get("/" + bucket)
            .then().statusCode(200).body("ListBucketResult.Contents.Key", contains("c", "d"));
    }

    @Test
    void multipartUploadAndCopy() throws Exception {
        String bucket = bucket();
        s3().when().put("/" + bucket).then().statusCode(200);
        String uploadId = s3().when().post("/" + bucket + "/big.bin?uploads")
            .then().statusCode(200).extract().path("InitiateMultipartUploadResult.UploadId");

        byte[] part1 = "first-part-".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "second".getBytes(StandardCharsets.UTF_8);
        String etag1 = s3().body(part1).when().put("/" + bucket + "/big.bin?partNumber=1&uploadId=" + uploadId)
            .then().statusCode(200).extract().header("ETag");
        String etag2 = s3().body(part2).when().put("/" + bucket + "/big.bin?partNumber=2&uploadId=" + uploadId)
            .then().statusCode(200).extract().header("ETag");

        s3().when().get("/" + bucket + "/big.bin?uploadId=" + uploadId)
            .then().statusCode(200).body("ListPartsResult.Part.PartNumber", contains("1", "2"));

        String complete = "<CompleteMultipartUpload>"
                + "<Part><PartNumber>1</PartNumber><ETag>" + etag1 + "</ETag></Part>"
                + "<Part><PartNumber>2</PartNumber><ETag>" + etag2 + "</ETag></Part>"
                + "</CompleteMultipartUpload>";
        s3().body(complete).when().post("/" + bucket + "/big.bin?uploadId=" + uploadId)
            .then().statusCode(200).body("CompleteMultipartUploadResult.ETag", endsWith("-2\""));

        s3().when().get("/" + bucket + "/big.bin").then().statusCode(200).body(equalTo("first-part-second"));

        s3().header("x-amz-copy-source", "/" + bucket + "/big.bin")
            .when().put("/" + bucket + "/copy.bin")
            .then().statusCode(200).body("CopyObjectResult.ETag", notNullValue());
        s3().when().get("/" + bucket + "/copy.bin").then().statusCode(200).body(equalTo("first-part-second"));

        String delete = "<Delete><Object><Key>big.bin</Key></Object><Object><Key>copy.bin</Key></Object>"
                + "<Object><Key>missing</Key></Object></Delete>";
        s3().body(delete).when().post("/" + bucket + "?delete")
            .then().statusCode(200).body("DeleteResult.Deleted.Key", hasItems("big.bin", "copy.bin", "missing"));
        s3().when().delete("/" + bucket).then().statusCode(204);
    }

    @Test
    void awsChunkedUploadIsDecoded() {
        String bucket = bucket();
        s3().when().put("/" + bucket).then().statusCode(200);
        String chunked = "5;chunk-signature=aa\r\nhello\r\n0;chunk-signature=bb\r\n\r\n";
        s3().header("Content-Encoding", "aws-chunked")
                .header("x-amz-content-sha256", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD")
                .header("x-amz-decoded-content-length", "5")
                .body(chunked.getBytes(StandardCharsets.ISO_8859_1))
            .when().put("/" + bucket + "/chunked.txt")
            .then().statusCode(200);
        s3().when().get("/" + bucket + "/chunked.txt").then().statusCode(200).body(equalTo("hello"));
    }

    @Test
    void errorsAreS3Xml() {
        s3().when().get("/no-such-bucket-s3it/key")
            .then().statusCode(404)
                .contentType(containsString("xml"))
                .body("Error.Code", equalTo("NoSuchBucket"));

        String bucket = bucket();
        s3().when().put("/" + bucket).then().statusCode(200);
        s3().when().get("/" + bucket + "/missing")
            .then().statusCode(404).body("Error.Code", equalTo("NoSuchKey"));
        s3().body("x").when().put("/" + bucket + "/k").then().statusCode(200);
        s3().when().delete("/" + bucket).then().statusCode(409).body("Error.Code", equalTo("BucketNotEmpty"));
    }

    @Test
    void customerSecretKeyResolvesTheCaller() {
        String userId = given().contentType("application/json")
                .body(Map.of("name", "s3user-" + System.nanoTime(), "description", "s3"))
            .when().post("/20160918/users")
            .then().statusCode(200).extract().path("id");

        String accessKey = given().contentType("application/json").body(Map.of("displayName", "s3"))
            .when().post("/20160918/users/" + userId + "/customerSecretKeys")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("key", notNullValue())
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("userId", equalTo(userId))
                .extract().path("id");

        // Bare array of summaries: the secret is never shown again.
        given().when().get("/20160918/users/" + userId + "/customerSecretKeys")
            .then().statusCode(200)
                .body("[0].id", equalTo(accessKey))
                .body("[0].key", nullValue());

        String bucket = bucket();
        s3(accessKey).when().put("/" + bucket).then().statusCode(200);
        given().when().get("/n/" + NAMESPACE + "/b/" + bucket)
            .then().statusCode(200).body("createdBy", equalTo(userId));

        given().contentType("application/json").body(Map.of("displayName", "renamed"))
            .when().put("/20160918/users/" + userId + "/customerSecretKeys/" + accessKey)
            .then().statusCode(200).body("displayName", equalTo("renamed"));
        given().when().delete("/20160918/users/" + userId + "/customerSecretKeys/" + accessKey)
            .then().statusCode(204);
    }

    @Test
    void folderListingsResumeAfterTheWholeFolder() {
        String bucket = bucket();
        s3().when().put("/" + bucket).then().statusCode(200);
        for (String key : new String[]{"a/1", "a/2", "b"}) {
            s3().body(key).when().put("/" + bucket + "/" + key).then().statusCode(200);
        }

        String marker = s3().queryParam("delimiter", "/").queryParam("max-keys", "1")
            .when().get("/" + bucket)
            .then().statusCode(200)
                .body("ListBucketResult.IsTruncated", equalTo("true"))
                .body("ListBucketResult.CommonPrefixes.Prefix", equalTo("a/"))
                .body("ListBucketResult.NextMarker", equalTo("a/"))
                .extract().path("ListBucketResult.NextMarker");
        s3().queryParam("delimiter", "/").queryParam("max-keys", "1").queryParam("marker", marker)
            .when().get("/" + bucket)
            .then().statusCode(200)
                .body("ListBucketResult.IsTruncated", equalTo("false"))
                .body("ListBucketResult.Contents.Key", equalTo("b"))
                .body(not(containsString("<CommonPrefixes>")));

        String token = s3().queryParam("list-type", "2").queryParam("delimiter", "/").queryParam("max-keys", "1")
            .when().get("/" + bucket)
            .then().statusCode(200)
                .body("ListBucketResult.IsTruncated", equalTo("true"))
                .body("ListBucketResult.CommonPrefixes.Prefix", equalTo("a/"))
                .extract().path("ListBucketResult.NextContinuationToken");
        s3().queryParam("list-type", "2").queryParam("delimiter", "/").queryParam("max-keys", "1")
                .queryParam("continuation-token", token)
            .when().get("/" + bucket)
            .then().statusCode(200)
                .body("ListBucketResult.IsTruncated", equalTo("false"))
                .body("ListBucketResult.Contents.Key", equalTo("b"));

        // A folder that is the last thing left is not reported as truncated.
        s3().queryParam("list-type", "2").queryParam("delimiter", "/").queryParam("prefix", "a")
                .queryParam("max-keys", "1")
            .when().get("/" + bucket)
            .then().statusCode(200)
                .body("ListBucketResult.CommonPrefixes.Prefix", equalTo("a/"))
                .body("ListBucketResult.IsTruncated", equalTo("false"));
    }

    @Test
    void keysAndTagValuesKeepTheirSpaces() {
        String bucket = bucket();
        s3().when().put("/" + bucket).then().statusCode(200);
        s3().body("spaced").when().put("/" + bucket + "/%20report%20").then().statusCode(200);
        s3().body("plain").when().put("/" + bucket + "/report").then().statusCode(200);

        s3().body("<Delete><Object><Key> report </Key></Object></Delete>")
            .when().post("/" + bucket + "?delete")
            .then().statusCode(200).body("DeleteResult.Deleted.Key", equalTo(" report "));
        s3().when().get("/" + bucket + "/%20report%20").then().statusCode(404);
        s3().when().get("/" + bucket + "/report").then().statusCode(200).body(equalTo("plain"));

        s3().body("<Tagging><TagSet><Tag><Key>team</Key><Value> blue </Value></Tag></TagSet></Tagging>")
            .when().put("/" + bucket + "?tagging")
            .then().statusCode(204);
        given().when().get("/n/" + NAMESPACE + "/b/" + bucket)
            .then().statusCode(200).body("freeformTags.team", equalTo(" blue "));
    }

    @Test
    void copySourceSplitsTheVersionQueryBeforeDecoding() {
        String bucket = bucket();
        s3().when().put("/" + bucket).then().statusCode(200);
        s3().body("question").when().put("/" + bucket + "/report%3FversionId%3Dnotes").then().statusCode(200);
        s3().body("plain").when().put("/" + bucket + "/report").then().statusCode(200);

        s3().header("x-amz-copy-source", "/" + bucket + "/report%3FversionId%3Dnotes")
            .when().put("/" + bucket + "/copy1")
            .then().statusCode(200);
        s3().when().get("/" + bucket + "/copy1").then().statusCode(200).body(equalTo("question"));

        s3().header("x-amz-copy-source", "/" + bucket + "/report?versionId=null")
            .when().put("/" + bucket + "/copy2")
            .then().statusCode(200);
        s3().when().get("/" + bucket + "/copy2").then().statusCode(200).body(equalTo("plain"));
    }

    @Test
    void completeRejectsMissingEtagsAndUnorderedParts() {
        String bucket = bucket();
        s3().when().put("/" + bucket).then().statusCode(200);
        String uploadId = s3().when().post("/" + bucket + "/mp.bin?uploads")
            .then().statusCode(200).extract().path("InitiateMultipartUploadResult.UploadId");
        String etag1 = s3().body("one").when().put("/" + bucket + "/mp.bin?partNumber=1&uploadId=" + uploadId)
            .then().statusCode(200).extract().header("ETag");
        String etag2 = s3().body("two").when().put("/" + bucket + "/mp.bin?partNumber=2&uploadId=" + uploadId)
            .then().statusCode(200).extract().header("ETag");
        String path = "/" + bucket + "/mp.bin?uploadId=" + uploadId;

        s3().body("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber></Part></CompleteMultipartUpload>")
            .when().post(path)
            .then().statusCode(400).body("Error.Code", equalTo("InvalidPart"));
        s3().body("<CompleteMultipartUpload>"
                    + "<Part><PartNumber>1</PartNumber><ETag>" + etag1 + "</ETag></Part>"
                    + "<Part><PartNumber>1</PartNumber><ETag>" + etag1 + "</ETag></Part>"
                    + "</CompleteMultipartUpload>")
            .when().post(path)
            .then().statusCode(400).body("Error.Code", equalTo("InvalidPartOrder"));
        s3().body("<CompleteMultipartUpload>"
                    + "<Part><PartNumber>2</PartNumber><ETag>" + etag2 + "</ETag></Part>"
                    + "<Part><PartNumber>1</PartNumber><ETag>" + etag1 + "</ETag></Part>"
                    + "</CompleteMultipartUpload>")
            .when().post(path)
            .then().statusCode(400).body("Error.Code", equalTo("InvalidPartOrder"));
        s3().when().get("/" + bucket + "/mp.bin").then().statusCode(404);
    }

    @Test
    void conditionalPutsUseTheS3Etag() throws Exception {
        String bucket = bucket();
        s3().when().put("/" + bucket).then().statusCode(200);
        String path = "/" + bucket + "/cond.txt";

        s3().header("If-Match", "\"abc\"").body("x").when().put(path)
            .then().statusCode(404).body("Error.Code", equalTo("NoSuchKey"));
        s3().header("If-None-Match", "*").body("first").when().put(path).then().statusCode(200);
        s3().header("If-None-Match", "*").body("second").when().put(path)
            .then().statusCode(412).body("Error.Code", equalTo("PreconditionFailed"));

        String etag = "\"" + md5Hex("first".getBytes(StandardCharsets.UTF_8)) + "\"";
        s3().header("If-Match", "\"0000\"").body("second").when().put(path)
            .then().statusCode(412).body("Error.Code", equalTo("PreconditionFailed"));
        s3().header("If-None-Match", etag).body("second").when().put(path)
            .then().statusCode(412).body("Error.Code", equalTo("PreconditionFailed"));
        s3().header("If-Match", etag).body("second").when().put(path).then().statusCode(200);
        s3().when().get(path).then().statusCode(200).body(equalTo("second"));
    }

    @Test
    void deletingAUserRevokesItsAccessKeys() {
        String userId = given().contentType("application/json")
                .body(Map.of("name", "s3gone-" + System.nanoTime(), "description", "s3"))
            .when().post("/20160918/users")
            .then().statusCode(200).extract().path("id");
        String accessKey = given().contentType("application/json").body(Map.of("displayName", "s3"))
            .when().post("/20160918/users/" + userId + "/customerSecretKeys")
            .then().statusCode(200).extract().path("id");
        given().when().delete("/20160918/users/" + userId).then().statusCode(204);

        String bucket = bucket();
        s3(accessKey).when().put("/" + bucket).then().statusCode(200);
        given().when().get("/n/" + NAMESPACE + "/b/" + bucket)
            .then().statusCode(200).body("createdBy", not(equalTo(userId)));
    }

    @Test
    void unsupportedBucketSubresourcesNeverRunTheBucketOperation() {
        String bucket = bucket();
        s3().when().put("/" + bucket).then().statusCode(200);

        s3().when().delete("/" + bucket + "?policy")
            .then().statusCode(501).body("Error.Code", equalTo("NotImplemented"));
        s3().body("{}").when().put("/" + bucket + "?policy")
            .then().statusCode(501).body("Error.Code", equalTo("NotImplemented"));
        s3().when().get("/" + bucket + "?acl")
            .then().statusCode(501).body("Error.Code", equalTo("NotImplemented"));
        s3().when().head("/" + bucket).then().statusCode(200);
        s3().when().delete("/" + bucket).then().statusCode(204);
    }

    @Test
    void urlEncodingTypeEncodesNamesInListings() {
        String bucket = bucket();
        s3().when().put("/" + bucket).then().statusCode(200);
        s3().body("x").when().put("/" + bucket + "/dir/a%20b%01c").then().statusCode(200);

        s3().queryParam("encoding-type", "url").when().get("/" + bucket)
            .then().statusCode(200)
                .body("ListBucketResult.EncodingType", equalTo("url"))
                .body("ListBucketResult.Contents.Key", equalTo("dir/a%20b%01c"));
        s3().queryParam("list-type", "2").queryParam("encoding-type", "url").queryParam("delimiter", "/")
            .when().get("/" + bucket)
            .then().statusCode(200)
                .body("ListBucketResult.EncodingType", equalTo("url"))
                .body("ListBucketResult.CommonPrefixes.Prefix", equalTo("dir/"));
    }

    @Test
    void conditionalReadsUseTheS3Etag() throws Exception {
        String bucket = bucket();
        s3().when().put("/" + bucket).then().statusCode(200);
        byte[] data = "conditional".getBytes(StandardCharsets.UTF_8);
        s3().body(data).when().put("/" + bucket + "/k").then().statusCode(200);
        String etag = "\"" + md5Hex(data) + "\"";

        s3().header("If-Match", etag).when().get("/" + bucket + "/k").then().statusCode(200);
        s3().header("If-Match", "\"other\"").when().get("/" + bucket + "/k")
            .then().statusCode(412).body("Error.Code", equalTo("PreconditionFailed"));
        s3().header("If-Match", "\"other\"").header("Range", "bytes=0-1").when().get("/" + bucket + "/k")
            .then().statusCode(412);
        s3().header("If-None-Match", etag).when().get("/" + bucket + "/k")
            .then().statusCode(304).header("ETag", etag);
        s3().header("If-Match", "\"other\"").when().head("/" + bucket + "/k").then().statusCode(412);
        s3().header("If-None-Match", etag).when().head("/" + bucket + "/k").then().statusCode(304);
        s3().header("If-None-Match", "\"other\"").when().head("/" + bucket + "/k").then().statusCode(200);
    }

    @Test
    void copySourceConditionsUseTheSourceEtag() throws Exception {
        String bucket = bucket();
        s3().when().put("/" + bucket).then().statusCode(200);
        byte[] data = "source".getBytes(StandardCharsets.UTF_8);
        s3().body(data).when().put("/" + bucket + "/src").then().statusCode(200);
        String etag = "\"" + md5Hex(data) + "\"";

        s3().header("x-amz-copy-source", "/" + bucket + "/src").header("x-amz-copy-source-if-match", "\"old\"")
            .when().put("/" + bucket + "/dst")
            .then().statusCode(412).body("Error.Code", equalTo("PreconditionFailed"));
        s3().header("x-amz-copy-source", "/" + bucket + "/src").header("x-amz-copy-source-if-none-match", etag)
                .header("x-amz-metadata-directive", "REPLACE")
            .when().put("/" + bucket + "/dst")
            .then().statusCode(412);
        s3().when().head("/" + bucket + "/dst").then().statusCode(404);

        s3().header("x-amz-copy-source", "/" + bucket + "/src").header("x-amz-copy-source-if-match", etag)
            .when().put("/" + bucket + "/dst")
            .then().statusCode(200);
        s3().when().get("/" + bucket + "/dst").then().statusCode(200).body(equalTo("source"));
    }

    @Test
    void startAfterInsideAFolderSkipsThatFolder() {
        String bucket = bucket();
        s3().when().put("/" + bucket).then().statusCode(200);
        for (String key : new String[]{"a/1", "a/2", "b"}) {
            s3().body(key).when().put("/" + bucket + "/" + key).then().statusCode(200);
        }

        s3().queryParam("list-type", "2").queryParam("delimiter", "/").queryParam("start-after", "a/1")
                .queryParam("max-keys", "1")
            .when().get("/" + bucket)
            .then().statusCode(200)
                .body("ListBucketResult.Contents.Key", equalTo("b"))
                .body(not(containsString("<CommonPrefixes>")))
                .body("ListBucketResult.IsTruncated", equalTo("false"));
        s3().queryParam("list-type", "2").queryParam("start-after", "a/1")
            .when().get("/" + bucket)
            .then().statusCode(200)
                .body("ListBucketResult.Contents.Key", hasItems("a/2", "b"));
    }

    @Test
    void nativeApiIsUntouchedWithoutTheCompatHost() {
        given().when().get("/n/" + NAMESPACE)
            .then().statusCode(200);
    }
}
