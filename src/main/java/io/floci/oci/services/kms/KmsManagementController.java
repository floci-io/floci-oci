package io.floci.oci.services.kms;

import io.floci.oci.core.common.OciPage;
import io.floci.oci.services.kms.model.StoredKey;
import io.floci.oci.services.kms.model.StoredKey.StoredKeyVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KMS key management plane — {@code /20180608/keys…}, served on the vault's
 * {@code managementEndpoint}. Real OCI gives each vault its own hostname; the SDKs reject
 * endpoints with a path, so the emulator serves every vault here and attaches new keys to
 * the caller's compartment vault (see {@code KmsService#resolveVaultForCompartment}).
 */
@Path("/20180608/keys")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KmsManagementController {

    private final KmsService service;

    @Inject
    public KmsManagementController(KmsService service) {
        this.service = service;
    }

    @POST
    public Response createKey(Map<String, Object> body) {
        Map<String, Object> keyShape = map(body, "keyShape");
        StoredKey key = service.createKey(null,
                str(body, "compartmentId"), str(body, "displayName"),
                str(keyShape, "algorithm"), integer(keyShape, "length"), str(keyShape, "curveId"),
                str(body, "protectionMode"),
                stringMap(body, "freeformTags"), definedTags(body));
        return Response.ok(keyJson(key)).header("etag", key.getEtag()).build();
    }

    @GET
    @Path("/{keyId}")
    public Response getKey(@PathParam("keyId") String keyId) {
        StoredKey key = service.getKey(null, keyId);
        return Response.ok(keyJson(key)).header("etag", key.getEtag()).build();
    }

    @GET
    public Response listKeys(@QueryParam("compartmentId") String compartmentId,
                             @QueryParam("limit") Integer limit,
                             @QueryParam("page") String page) {
        List<Map<String, Object>> summaries = service.listKeys(null, compartmentId).stream()
                .map(KmsManagementController::keySummaryJson).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(summaries, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @PUT
    @Path("/{keyId}")
    public Response updateKey(@PathParam("keyId") String keyId,
                              @HeaderParam("if-match") String ifMatch,
                              Map<String, Object> body) {
        StoredKey key = service.updateKey(null, keyId, str(body, "displayName"),
                stringMap(body, "freeformTags"), definedTags(body), ifMatch);
        return Response.ok(keyJson(key)).header("etag", key.getEtag()).build();
    }

    @POST
    @Path("/{keyId}/actions/enable")
    public Response enableKey(@PathParam("keyId") String keyId,
                              @HeaderParam("if-match") String ifMatch) {
        StoredKey key = service.setKeyEnabled(null, keyId, true, ifMatch);
        return Response.ok(keyJson(key)).header("etag", key.getEtag()).build();
    }

    @POST
    @Path("/{keyId}/actions/disable")
    public Response disableKey(@PathParam("keyId") String keyId,
                               @HeaderParam("if-match") String ifMatch) {
        StoredKey key = service.setKeyEnabled(null, keyId, false, ifMatch);
        return Response.ok(keyJson(key)).header("etag", key.getEtag()).build();
    }

    @POST
    @Path("/{keyId}/actions/scheduleDeletion")
    public Response scheduleKeyDeletion(@PathParam("keyId") String keyId,
                                        @HeaderParam("if-match") String ifMatch,
                                        Map<String, Object> body) {
        StoredKey key = service.scheduleKeyDeletion(null, keyId, str(body, "timeOfDeletion"), ifMatch);
        return Response.ok(keyJson(key)).header("etag", key.getEtag()).build();
    }

    @POST
    @Path("/{keyId}/actions/cancelDeletion")
    public Response cancelKeyDeletion(@PathParam("keyId") String keyId,
                                      @HeaderParam("if-match") String ifMatch) {
        StoredKey key = service.cancelKeyDeletion(null, keyId, ifMatch);
        return Response.ok(keyJson(key)).header("etag", key.getEtag()).build();
    }

    @POST
    @Path("/{keyId}/keyVersions")
    public Response createKeyVersion(@PathParam("keyId") String keyId) {
        StoredKey key = service.getKey(null, keyId);
        StoredKeyVersion version = service.createKeyVersion(null, keyId);
        return Response.ok(keyVersionJson(key, version))
                .header("etag", service.getKey(null, keyId).getEtag()).build();
    }

    @GET
    @Path("/{keyId}/keyVersions/{keyVersionId}")
    public Response getKeyVersion(@PathParam("keyId") String keyId,
                                  @PathParam("keyVersionId") String keyVersionId) {
        StoredKey key = service.getKey(null, keyId);
        StoredKeyVersion version = service.getKeyVersion(null, keyId, keyVersionId);
        return Response.ok(keyVersionJson(key, version)).header("etag", key.getEtag()).build();
    }

    @GET
    @Path("/{keyId}/keyVersions")
    public Response listKeyVersions(@PathParam("keyId") String keyId,
                                    @QueryParam("limit") Integer limit,
                                    @QueryParam("page") String page) {
        StoredKey key = service.getKey(null, keyId);
        List<Map<String, Object>> summaries = service.listKeyVersions(null, keyId).stream()
                .map(v -> keyVersionJson(key, v)).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(summaries, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    // ── Wire projections ───────────────────────────────────────────────────────

    private static Map<String, Object> keyJson(StoredKey key) {
        Map<String, Object> keyShape = new LinkedHashMap<>();
        keyShape.put("algorithm", key.getAlgorithm());
        keyShape.put("length", key.getLength());
        if (key.getCurveId() != null) {
            keyShape.put("curveId", key.getCurveId());
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("compartmentId", key.getCompartmentId());
        json.put("currentKeyVersion", key.getCurrentKeyVersionId());
        json.put("displayName", key.getDisplayName());
        json.put("id", key.getId());
        json.put("keyShape", keyShape);
        json.put("lifecycleState", key.getLifecycleState());
        json.put("timeCreated", key.getTimeCreated());
        json.put("vaultId", key.getVaultId());
        json.put("protectionMode", key.getProtectionMode());
        if (key.getTimeOfDeletion() != null) {
            json.put("timeOfDeletion", key.getTimeOfDeletion());
        }
        if (key.getFreeformTags() != null) {
            json.put("freeformTags", key.getFreeformTags());
        }
        if (key.getDefinedTags() != null) {
            json.put("definedTags", key.getDefinedTags());
        }
        return json;
    }

    /** KeySummary: no keyShape / currentKeyVersion, but a flat algorithm field. */
    private static Map<String, Object> keySummaryJson(StoredKey key) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("compartmentId", key.getCompartmentId());
        json.put("displayName", key.getDisplayName());
        json.put("id", key.getId());
        json.put("lifecycleState", key.getLifecycleState());
        json.put("timeCreated", key.getTimeCreated());
        json.put("vaultId", key.getVaultId());
        json.put("protectionMode", key.getProtectionMode());
        json.put("algorithm", key.getAlgorithm());
        if (key.getFreeformTags() != null) {
            json.put("freeformTags", key.getFreeformTags());
        }
        if (key.getDefinedTags() != null) {
            json.put("definedTags", key.getDefinedTags());
        }
        return json;
    }

    private static Map<String, Object> keyVersionJson(StoredKey key, StoredKeyVersion version) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("compartmentId", key.getCompartmentId());
        json.put("id", version.getId());
        json.put("keyId", key.getId());
        json.put("timeCreated", version.getTimeCreated());
        json.put("vaultId", key.getVaultId());
        json.put("lifecycleState", version.getLifecycleState());
        json.put("origin", version.getOrigin());
        if (version.getPublicKey() != null) {
            json.put("publicKey", version.getPublicKey());
        }
        return json;
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object value = body.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static Integer integer(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Number n ? n.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Map<?, ?> m ? (Map<String, String>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> definedTags(Map<String, Object> body) {
        return body != null && body.get("definedTags") instanceof Map<?, ?> m
                ? (Map<String, Map<String, Object>>) m : null;
    }
}
