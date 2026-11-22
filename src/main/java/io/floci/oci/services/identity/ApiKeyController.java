package io.floci.oci.services.identity;

import io.floci.oci.services.identity.model.StoredApiKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /20160918/users/{userId}/apiKeys} (oci-go-sdk/identity/identity_client.go): upload returns
 * the ApiKey with an {@code etag}; list returns a bare array, with no {@code limit} or {@code page}
 * (a user holds three keys at most); delete takes the fingerprint.
 */
@Path("/20160918/users/{userId}/apiKeys")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ApiKeyController {

    private final ApiKeyService service;

    @Inject
    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @POST
    public Response upload(@PathParam("userId") String userId, Map<String, Object> body) {
        Object pem = body == null ? null : body.get("key");
        StoredApiKey key = service.upload(userId, pem instanceof String s ? s : null);
        return Response.ok(json(key)).header("etag", key.getEtag()).build();
    }

    @GET
    public Response list(@PathParam("userId") String userId) {
        List<Map<String, Object>> keys = service.list(userId).stream().map(ApiKeyController::json).toList();
        return Response.ok(keys).build();
    }

    @DELETE
    @Path("/{fingerprint}")
    public Response delete(@PathParam("userId") String userId, @PathParam("fingerprint") String fingerprint,
                           @HeaderParam("if-match") String ifMatch) {
        service.delete(userId, fingerprint, ifMatch);
        return Response.noContent().build();
    }

    private static Map<String, Object> json(StoredApiKey key) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("keyId", key.getKeyId());
        json.put("keyValue", key.getKeyValue());
        json.put("fingerprint", key.getFingerprint());
        json.put("userId", key.getUserId());
        json.put("timeCreated", key.getTimeCreated());
        json.put("lifecycleState", key.getLifecycleState());
        return json;
    }
}
