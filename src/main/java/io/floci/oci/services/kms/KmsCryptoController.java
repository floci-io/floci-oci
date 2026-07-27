package io.floci.oci.services.kms;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KMS crypto data plane — {@code /20180608/{encrypt,decrypt,…}}, served on the vault's
 * {@code cryptoEndpoint}. Crypto is real (AES-GCM / RSA / ECDSA); the key's own record
 * carries its vault, so no vault scoping is needed here. Only {@code opc-request-id}
 * travels as a header.
 */
@Path("/20180608")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KmsCryptoController {

    private final KmsService service;

    @Inject
    public KmsCryptoController(KmsService service) {
        this.service = service;
    }

    @POST
    @Path("/encrypt")
    public Response encrypt(Map<String, Object> body) {
        KmsService.EncryptResult result = service.encrypt(null,
                str(body, "keyId"), str(body, "plaintext"), str(body, "keyVersionId"));
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ciphertext", result.ciphertext());
        json.put("keyId", result.keyId());
        json.put("keyVersionId", result.keyVersionId());
        return Response.ok(json).build();
    }

    @POST
    @Path("/decrypt")
    public Response decrypt(Map<String, Object> body) {
        KmsService.DecryptResult result = service.decrypt(null,
                str(body, "keyId"), str(body, "ciphertext"));
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("plaintext", result.plaintext());
        json.put("plaintextChecksum", result.plaintextChecksum());
        json.put("keyId", result.keyId());
        json.put("keyVersionId", result.keyVersionId());
        return Response.ok(json).build();
    }

    @POST
    @Path("/generateDataEncryptionKey")
    public Response generateDataEncryptionKey(Map<String, Object> body) {
        Map<String, Object> keyShape = body != null && body.get("keyShape") instanceof Map<?, ?> m
                ? castMap(m) : Map.of();
        int length = keyShape.get("length") instanceof Number n ? n.intValue() : 32;
        boolean includePlaintext = Boolean.TRUE.equals(body != null ? body.get("includePlaintextKey") : null);
        KmsService.GeneratedKeyResult result = service.generateDataEncryptionKey(null,
                str(body, "keyId"), includePlaintext, length);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ciphertext", result.ciphertext());
        if (result.plaintext() != null) {
            json.put("plaintext", result.plaintext());
            json.put("plaintextChecksum", result.plaintextChecksum());
        }
        return Response.ok(json).build();
    }

    @POST
    @Path("/sign")
    public Response sign(Map<String, Object> body) {
        KmsService.SignResult result = service.sign(null, str(body, "keyId"),
                str(body, "message"), str(body, "signingAlgorithm"), str(body, "keyVersionId"));
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("keyId", result.keyId());
        json.put("keyVersionId", result.keyVersionId());
        json.put("signature", result.signature());
        json.put("signingAlgorithm", result.signingAlgorithm());
        return Response.ok(json).build();
    }

    @POST
    @Path("/verify")
    public Response verify(Map<String, Object> body) {
        boolean valid = service.verify(null, str(body, "keyId"), str(body, "keyVersionId"),
                str(body, "signature"), str(body, "message"), str(body, "signingAlgorithm"));
        return Response.ok(Map.of("isSignatureValid", valid)).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
