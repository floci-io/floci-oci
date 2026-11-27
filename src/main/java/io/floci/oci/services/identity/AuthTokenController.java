package io.floci.oci.services.identity;

import io.floci.oci.services.identity.model.StoredAuthToken;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /20160918/users/{userId}/authTokens} (oci-go-sdk/identity/identity_client.go): create returns
 * the AuthToken with its {@code token}, the only response that carries it; list returns a bare array
 * and takes no {@code limit} or {@code page} (list_auth_tokens_request_response.go); update returns the
 * AuthToken without its token. Create and update carry an {@code etag} header.
 */
@Path("/20160918/users/{userId}/authTokens")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthTokenController {

    private final AuthTokenService service;

    @Inject
    public AuthTokenController(AuthTokenService service) {
        this.service = service;
    }

    @POST
    public Response create(@PathParam("userId") String userId, Map<String, Object> body) {
        Object description = body == null ? null : body.get("description");
        AuthTokenService.CreatedToken created = service.create(userId,
                description instanceof String s ? s : null);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("token", created.value());
        json.putAll(json(created.token()));
        return Response.ok(json).header("etag", created.token().getEtag()).build();
    }

    @GET
    public Response list(@PathParam("userId") String userId) {
        List<Map<String, Object>> tokens = service.list(userId).stream().map(AuthTokenController::json).toList();
        return Response.ok(tokens).build();
    }

    @PUT
    @Path("/{authTokenId}")
    public Response update(@PathParam("userId") String userId, @PathParam("authTokenId") String tokenId,
                           @HeaderParam("if-match") String ifMatch, Map<String, Object> body) {
        Object description = body == null ? null : body.get("description");
        StoredAuthToken token = service.update(userId, tokenId, description instanceof String s ? s : null,
                ifMatch);
        return Response.ok(json(token)).header("etag", token.getEtag()).build();
    }

    @DELETE
    @Path("/{authTokenId}")
    public Response delete(@PathParam("userId") String userId, @PathParam("authTokenId") String tokenId,
                           @HeaderParam("if-match") String ifMatch) {
        service.delete(userId, tokenId, ifMatch);
        return Response.noContent().build();
    }

    /** The AuthToken fields other than {@code token}; tokens never expire, so {@code timeExpires} is null. */
    private static Map<String, Object> json(StoredAuthToken token) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", token.getId());
        json.put("userId", token.getUserId());
        json.put("description", token.getDescription());
        json.put("timeCreated", token.getTimeCreated());
        json.put("timeExpires", null);
        json.put("lifecycleState", token.getLifecycleState());
        json.put("inactiveStatus", null);
        return json;
    }
}
