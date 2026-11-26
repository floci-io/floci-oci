package io.floci.oci.core.auth;

import io.floci.oci.testing.OciRequestSigner;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.specification.RequestSpecification;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * RestAssured requests signed by {@link OciRequestSigner}, sent with exactly the bytes that were signed:
 * a fixed {@code Host}, no URL re-encoding and no charset appended to the content type.
 */
final class SignedRequests {

    static final String HOST = "localhost";
    static final String TENANCY = "ocid1.tenancy.oc1..flocitesttenancy00000000000000000000000000000000000000000";

    private static final RestAssuredConfig AS_SIGNED = RestAssuredConfig.config()
            .encoderConfig(EncoderConfig.encoderConfig().appendDefaultContentCharsetToContentTypeIfUndefined(false));

    private SignedRequests() {
    }

    /** A request signed over the generic headers only. */
    static RequestSpecification signed(OciRequestSigner signer, String method, String target) {
        return send(signer.sign(method, target, HOST, Instant.now(), null, null), null);
    }

    /** A request signed over the generic headers only, at {@code date}. */
    static RequestSpecification signedAt(OciRequestSigner signer, String method, String target, Instant date) {
        return send(signer.sign(method, target, HOST, date, null, null), null);
    }

    /** A JSON request signed over its body, which sends {@code sent} (the signed body unless tampering). */
    static RequestSpecification signedJson(OciRequestSigner signer, String method, String target, String signedBody,
                                           String sent) {
        Map<String, String> headers = signer.sign(method, target, HOST, Instant.now(),
                signedBody.getBytes(StandardCharsets.UTF_8), "application/json");
        return send(headers, sent.getBytes(StandardCharsets.UTF_8));
    }

    /** A JSON request whose signature leaves the body out, as Go's PutObject signs. */
    static RequestSpecification signedWithoutBody(OciRequestSigner signer, String method, String target, String body) {
        return send(signer.sign(method, target, HOST, Instant.now(), null, null), body.getBytes(StandardCharsets.UTF_8))
                .contentType("application/json");
    }

    private static RequestSpecification send(Map<String, String> headers, byte[] body) {
        RequestSpecification request = given().config(AS_SIGNED).urlEncodingEnabled(false);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            // RestAssured's client computes Content-Length from the body it sends.
            if (!"content-length".equals(header.getKey())) {
                request.header(header.getKey(), header.getValue());
            }
        }
        if (body != null) {
            request.body(body);
        }
        return request;
    }
}
