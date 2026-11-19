package io.floci.oci.services.identity;

import io.floci.oci.core.common.OciPage;
import io.floci.oci.services.identity.model.StoredCustomerSecretKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
 * {@code /20160918/users/{userId}/customerSecretKeys} (oci-go-sdk/identity/identity_client.go):
 * create returns the CustomerSecretKey with its {@code key} (the secret, shown only here); list
 * returns a bare array of CustomerSecretKeySummary; update returns the summary.
 */
@Path("/20160918/users/{userId}/customerSecretKeys")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CustomerSecretKeyController {

    private final CustomerSecretKeyService service;

    @Inject
    public CustomerSecretKeyController(CustomerSecretKeyService service) {
        this.service = service;
    }

    @POST
    public Response create(@PathParam("userId") String userId, Map<String, Object> body) {
        Object displayName = body == null ? null : body.get("displayName");
        CustomerSecretKeyService.CreatedKey created = service.create(userId,
                displayName instanceof String s ? s : null);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("key", created.secret());
        json.putAll(summary(created.key()));
        return Response.ok(json).header("etag", created.key().getEtag()).build();
    }

    @GET
    public Response list(@PathParam("userId") String userId, @QueryParam("limit") Integer limit,
                         @QueryParam("page") String page) {
        List<Map<String, Object>> all = service.list(userId).stream().map(CustomerSecretKeyController::summary).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(all, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @PUT
    @Path("/{customerSecretKeyId}")
    public Response update(@PathParam("userId") String userId, @PathParam("customerSecretKeyId") String keyId,
                           @HeaderParam("if-match") String ifMatch, Map<String, Object> body) {
        Object displayName = body == null ? null : body.get("displayName");
        StoredCustomerSecretKey key = service.update(userId, keyId, displayName instanceof String s ? s : null,
                ifMatch);
        return Response.ok(summary(key)).header("etag", key.getEtag()).build();
    }

    @DELETE
    @Path("/{customerSecretKeyId}")
    public Response delete(@PathParam("userId") String userId, @PathParam("customerSecretKeyId") String keyId,
                           @HeaderParam("if-match") String ifMatch) {
        service.delete(userId, keyId, ifMatch);
        return Response.noContent().build();
    }

    private static Map<String, Object> summary(StoredCustomerSecretKey key) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", key.getId());
        json.put("userId", key.getUserId());
        json.put("displayName", key.getDisplayName());
        json.put("timeCreated", key.getTimeCreated());
        json.put("lifecycleState", key.getLifecycleState());
        return json;
    }
}
