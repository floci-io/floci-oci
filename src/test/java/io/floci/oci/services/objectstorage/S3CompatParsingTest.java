package io.floci.oci.services.objectstorage;

import io.floci.oci.core.auth.AccessKeyResolver;
import io.floci.oci.services.objectstorage.model.StoredOsObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class S3CompatParsingTest {

    @Test
    void compatHostNamesTheNamespace() {
        assertEquals("axzbp3kgiaim", S3CompatHostFilter.namespaceOf(
                "axzbp3kgiaim.compat.objectstorage.us-ashburn-1.oraclecloud.com").orElseThrow());
        assertEquals("floci-local", S3CompatHostFilter.namespaceOf(
                "floci-local.compat.objectstorage.us-ashburn-1.localhost.floci.io:4599").orElseThrow());
        assertTrue(S3CompatHostFilter.namespaceOf("objectstorage.us-ashburn-1.oraclecloud.com").isEmpty());
        assertTrue(S3CompatHostFilter.namespaceOf("localhost:4599").isEmpty());
        assertTrue(S3CompatHostFilter.namespaceOf(null).isEmpty());
    }

    @Test
    void copySourceQueriesNameAVersion() {
        assertEquals("v1", S3CompatResource.versionIdIn("versionId=v1"));
        assertEquals("a/b", S3CompatResource.versionIdIn("x=1&versionId=a%2Fb"));
        assertNull(S3CompatResource.versionIdIn("versionId=null"));
        assertNull(S3CompatResource.versionIdIn("versionId="));
        assertNull(S3CompatResource.versionIdIn("other=1"));
        assertNull(S3CompatResource.requestedVersion(null));
    }

    @Test
    void sigV4AccessKeyIsReadFromTheAuthorizationHeader() {
        assertEquals("AKIDEXAMPLE", AccessKeyResolver.sigV4AccessKey(
                "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261118/us-ashburn-1/s3/aws4_request, "
                        + "SignedHeaders=host;x-amz-date, Signature=abc").orElseThrow());
        assertTrue(AccessKeyResolver.sigV4AccessKey("Signature version=\"1\",keyId=\"a/b/c\"").isEmpty());
        assertEquals("AKID", AccessKeyResolver.presignedAccessKey("AKID/20261118/us-ashburn-1/s3/aws4_request")
                .orElseThrow());
    }

    @Test
    void rangesFollowRfc7233() {
        assertArrayEquals(new long[]{0, 4}, S3CompatResource.parseRange("0-4", 10));
        assertArrayEquals(new long[]{5, 9}, S3CompatResource.parseRange("5-", 10));
        assertArrayEquals(new long[]{7, 9}, S3CompatResource.parseRange("-3", 10));
        assertArrayEquals(new long[]{8, 9}, S3CompatResource.parseRange("8-100", 10));
        assertNull(S3CompatResource.parseRange("10-12", 10));
        assertNull(S3CompatResource.parseRange("0-1,3-4", 10));
    }

    @Test
    void etagsAreHexMd5AndMultipartKeepsThePartCount() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        StoredOsObject single = new StoredOsObject();
        single.setMd5(ObjectStorageService.md5Base64(data));
        assertEquals("\"5d41402abc4b2a76b9719d911017c592\"", S3CompatResource.quotedEtag(single));

        StoredOsObject multipart = new StoredOsObject();
        multipart.setMd5(single.getMd5());
        byte[] digest = HexFormat.of().parseHex("0123456789abcdef0123456789abcdef");
        multipart.setMultipartMd5(Base64.getEncoder().encodeToString(digest) + "-3");
        assertEquals("\"0123456789abcdef0123456789abcdef-3\"", S3CompatResource.quotedEtag(multipart));
    }

    @Test
    void awsChunkedBodyIsDecodedWithSignaturesAndTrailers() throws Exception {
        String body = "5;chunk-signature=aaaa\r\nhello\r\n6;chunk-signature=bbbb\r\n world\r\n"
                + "0;chunk-signature=cccc\r\nx-amz-checksum-crc32:AAAAAA==\r\n\r\n";
        try (AwsChunkedInputStream in = new AwsChunkedInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.ISO_8859_1)))) {
            assertEquals("hello world", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void truncatedAwsChunkedBodyFails() {
        String body = "a\r\nhello";
        assertThrows(IOException.class, () -> new AwsChunkedInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.ISO_8859_1))).readAllBytes());
    }

    @Test
    void xmlIsEscaped() {
        assertEquals("a&amp;b&lt;c&gt;&quot;&apos;", S3Xml.escape("a&b<c>\"'"));
    }

    @Test
    void xmlWithADoctypeIsRefused() {
        byte[] hostile = ("<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<Delete><Object><Key>&x;</Key></Object></Delete>").getBytes(StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> S3Xml.parse(hostile));
    }
}
