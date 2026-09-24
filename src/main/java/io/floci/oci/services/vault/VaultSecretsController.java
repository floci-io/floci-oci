package io.floci.oci.services.vault;

import io.floci.oci.core.common.OciPage;
import io.floci.oci.services.vault.model.StoredVaultSecret;
import io.floci.oci.services.vault.model.StoredVaultSecret.StoredSecretVersion;
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
 * Vault secrets management plane (VaultsClient) — {@code /20180608/secrets…}.
 * Note the path asymmetry from real OCI: single versions live at {@code /version/{n}}
 * (singular) while the list is {@code /versions} (plural). The {@code Secret} wire
 * shape never echoes content.
 */
@Path("/20180608/secrets")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VaultSecretsController {

    private final VaultSecretsService service;

    @Inject
    public VaultSecretsController(VaultSecretsService service) {
        this.service = service;
    }

    @POST
    public Response createSecret(Map<String, Object> body) {
        StoredVaultSecret secret = service.createSecret(
                str(body, "compartmentId"), str(body, "vaultId"), str(body, "keyId"),
                str(body, "secretName"), str(body, "description"),
                map(body, "secretContent"), map(body, "metadata"),
                stringMap(body, "freeformTags"), definedTags(body));
        return Response.ok(secretJson(secret)).header("etag", secret.getEtag()).build();
    }

    @GET
    @Path("/{secretId}")
    public Response getSecret(@PathParam("secretId") String secretId) {
        StoredVaultSecret secret = service.getSecret(secretId);
        return Response.ok(secretJson(secret)).header("etag", secret.getEtag()).build();
    }

    @GET
    public Response listSecrets(@QueryParam("compartmentId") String compartmentId,
                                @QueryParam("vaultId") String vaultId,
                                @QueryParam("name") String name,
                                @QueryParam("limit") Integer limit,
                                @QueryParam("page") String page) {
        List<Map<String, Object>> summaries = service.listSecrets(compartmentId, vaultId, name)
                .stream().map(VaultSecretsController::secretJson).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(summaries, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @PUT
    @Path("/{secretId}")
    public Response updateSecret(@PathParam("secretId") String secretId,
                                 @HeaderParam("if-match") String ifMatch,
                                 Map<String, Object> body) {
        StoredVaultSecret secret = service.updateSecret(secretId, str(body, "description"),
                map(body, "secretContent"), map(body, "metadata"),
                stringMap(body, "freeformTags"), definedTags(body), ifMatch);
        return Response.ok(secretJson(secret)).header("etag", secret.getEtag()).build();
    }

    @POST
    @Path("/{secretId}/actions/scheduleDeletion")
    public Response scheduleDeletion(@PathParam("secretId") String secretId,
                                     @HeaderParam("if-match") String ifMatch,
                                     Map<String, Object> body) {
        StoredVaultSecret secret =
                service.scheduleSecretDeletion(secretId, str(body, "timeOfDeletion"), ifMatch);
        // No body on the wire — only etag + opc-request-id.
        return Response.ok().header("etag", secret.getEtag()).build();
    }

    @POST
    @Path("/{secretId}/actions/cancelDeletion")
    public Response cancelDeletion(@PathParam("secretId") String secretId,
                                   @HeaderParam("if-match") String ifMatch) {
        StoredVaultSecret secret = service.cancelSecretDeletion(secretId, ifMatch);
        return Response.ok().header("etag", secret.getEtag()).build();
    }

    @POST
    @Path("/{secretId}/actions/changeCompartment")
    public Response changeCompartment(@PathParam("secretId") String secretId,
                                      @HeaderParam("if-match") String ifMatch,
                                      Map<String, Object> body) {
        service.changeSecretCompartment(secretId, str(body, "compartmentId"), ifMatch);
        return Response.ok().build();
    }

    @GET
    @Path("/{secretId}/version/{secretVersionNumber}")
    public Response getSecretVersion(@PathParam("secretId") String secretId,
                                     @PathParam("secretVersionNumber") long versionNumber) {
        StoredSecretVersion version = service.getSecretVersion(secretId, versionNumber);
        return Response.ok(versionJson(secretId, version)).build();
    }

    @GET
    @Path("/{secretId}/versions")
    public Response listSecretVersions(@PathParam("secretId") String secretId,
                                       @QueryParam("limit") Integer limit,
                                       @QueryParam("page") String page) {
        List<Map<String, Object>> versions = service.listSecretVersions(secretId).stream()
                .map(v -> versionJson(secretId, v)).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(versions, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    // ── Wire projections ───────────────────────────────────────────────────────

    /** The Secret shape — deliberately content-free. */
    static Map<String, Object> secretJson(StoredVaultSecret secret) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("compartmentId", secret.getCompartmentId());
        json.put("id", secret.getId());
        json.put("lifecycleState", secret.getLifecycleState());
        json.put("secretName", secret.getSecretName());
        json.put("timeCreated", secret.getTimeCreated());
        json.put("vaultId", secret.getVaultId());
        json.put("keyId", secret.getKeyId());
        if (secret.getCurrentVersionNumber() > 0) {
            json.put("currentVersionNumber", secret.getCurrentVersionNumber());
        }
        if (secret.getDescription() != null) {
            json.put("description", secret.getDescription());
        }
        if (secret.getMetadata() != null) {
            json.put("metadata", secret.getMetadata());
        }
        if (secret.getTimeOfDeletion() != null) {
            json.put("timeOfDeletion", secret.getTimeOfDeletion());
        }
        if (secret.getFreeformTags() != null) {
            json.put("freeformTags", secret.getFreeformTags());
        }
        if (secret.getDefinedTags() != null) {
            json.put("definedTags", secret.getDefinedTags());
        }
        return json;
    }

    private static Map<String, Object> versionJson(String secretId, StoredSecretVersion version) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("secretId", secretId);
        json.put("versionNumber", version.getVersionNumber());
        json.put("timeCreated", version.getTimeCreated());
        json.put("contentType", version.getContentType());
        json.put("stages", version.getStages());
        if (version.getName() != null) {
            json.put("name", version.getName());
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
