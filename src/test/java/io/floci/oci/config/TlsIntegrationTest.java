package io.floci.oci.config;

import io.floci.oci.core.tls.CertificateGenerator;
import io.floci.oci.core.tls.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.SSLConfig;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Integration test verifying TLS/HTTPS support when enabled via configuration.
 *
 * <p>Uses a {@link QuarkusTestProfile} to enable self-signed TLS so the Quarkus
 * HTTP server serves HTTPS alongside HTTP (dual-mode).
 */
@QuarkusTest
@TestProfile(TlsIntegrationTest.TlsEnabledProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TlsIntegrationTest {

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int testSslPort;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int testHttpPort;

    @BeforeAll
    void setupRestAssured() {
        // Trust self-signed certificates for all requests in this test class
        RestAssured.config = RestAssuredConfig.config()
                .sslConfig(SSLConfig.sslConfig().relaxedHTTPSValidation());
    }

    // ==================== HTTPS Connectivity ====================

    @Test
    void healthEndpointOverHttps() {
        given()
            .baseUri("https://localhost:" + testSslPort)
        .when()
            .get("/_floci-oci/health")
        .then()
            .statusCode(200)
            .body("edition", is("community"));
    }

    @Test
    void healthEndpointStillWorksOverHttp() {
        given()
            .baseUri("http://localhost:" + testHttpPort)
        .when()
            .get("/_floci-oci/health")
        .then()
            .statusCode(200)
            .body("edition", is("community"));
    }

    // ==================== Emulator info via HTTPS ====================

    @Test
    void infoEndpointOverHttps() {
        given()
            .baseUri("https://localhost:" + testSslPort)
        .when()
            .get("/_floci-oci/info")
        .then()
            .statusCode(200)
            .body("version", notNullValue());
    }

    @Test
    void tlsCertServesTheCertificateTheHttpsEndpointPresents() throws Exception {
        String pem = given()
            .baseUri("http://localhost:" + testHttpPort)
            .accept("application/x-pem-file")
        .when()
            .get("/_floci/tls-cert")
        .then()
            .statusCode(200)
            .contentType("application/x-pem-file")
            .body(startsWith("-----BEGIN CERTIFICATE-----"))
            .body(not(containsString("PRIVATE KEY")))
            .extract().asString();

        Certificate served = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        assertEquals(httpsPeerCertificate(), served);
    }

    /** The leaf certificate the HTTPS listener presents in its handshake. */
    private Certificate httpsPeerCertificate() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new TrustEveryCertificate()}, null);
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("localhost", testSslPort)) {
            socket.setSoTimeout(5_000);
            socket.startHandshake();
            return socket.getSession().getPeerCertificates()[0];
        }
    }

    /** Accepts any server certificate, so the test can read the one presented and compare it itself. */
    private static final class TrustEveryCertificate implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    @Test
    void manifestReportsTlsEnabled() {
        given()
            .baseUri("http://localhost:" + testHttpPort)
        .when()
            .get("/_floci/info")
        .then()
            .statusCode(200)
            .body("tls.enabled", is(true));
    }

    // ==================== Test Profile ====================

    public static final class TlsEnabledProfile implements QuarkusTestProfile {

        private static final Path CERT_DIR = Path.of("/tmp/floci-tls-integration-test");
        private static final Path CERT_FILE = CERT_DIR.resolve("test.crt");
        private static final Path KEY_FILE = CERT_DIR.resolve("test.key");

        static {
            generateCertIfNeeded();
        }

        private static void generateCertIfNeeded() {
            if (Files.exists(CERT_FILE) && Files.exists(KEY_FILE)) {
                return;
            }
            try {
                Files.createDirectories(CERT_DIR);
                if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                    Security.addProvider(new BouncyCastleProvider());
                }
                CertificateGenerator gen = new CertificateGenerator();
                CertificateGenerator.GeneratedCertificate cert = gen.generateCertificate(
                        "localhost", List.of("localhost", "127.0.0.1"), KeyAlgorithm.RSA_2048);
                Files.writeString(CERT_FILE, cert.certificatePem());
                Files.writeString(KEY_FILE, cert.privateKeyPem());
            } catch (IOException e) {
                throw new RuntimeException("Failed to generate test cert", e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-oci.tls.enabled", "true",
                    "floci-oci.tls.self-signed", "false",
                    "floci-oci.tls.cert-path", CERT_FILE.toAbsolutePath().toString(),
                    "floci-oci.tls.key-path", KEY_FILE.toAbsolutePath().toString(),
                    "floci-oci.storage.persistent-path", "/tmp/floci-tls-integration-test-data",
                    "quarkus.http.ssl.certificate.files", CERT_FILE.toAbsolutePath().toString(),
                    "quarkus.http.ssl.certificate.key-files", KEY_FILE.toAbsolutePath().toString(),
                    "quarkus.http.insecure-requests", "enabled"
            );
        }
    }
}
