package io.floci.oci.services.vault;

import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.services.vault.model.StoredVaultSecret.StoredSecretVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
 * Secret retrieval plane (SecretsClient) — {@code /20190301/secretbundles…}. Note real
 * OCI's oddity preserved here: GetSecretBundleByName is a POST with everything in the
 * query string, no body, and no etag on the response.
 */
@Path("/20190301/secretbundles")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SecretBundleController {

    private final VaultSecretsService service;

    @Inject
    public SecretBundleController(VaultSecretsService service) {
        this.service = service;
    }

    @GET
    @Path("/{secretId}")
    public Response getSecretBundle(@PathParam("secretId") String secretId,
                                    @QueryParam("versionNumber") Long versionNumber,
                                    @QueryParam("stage") String stage) {
        VaultSecretsService.Bundle bundle = service.getBundle(secretId, versionNumber, stage);
        return Response.ok(bundleJson(bundle)).header("etag", Etags.newEtag()).build();
    }

    @POST
    @Path("/actions/getByName")
    @Consumes(MediaType.WILDCARD) // the SDK sends this POST with no body and no content type
    public Response getSecretBundleByName(@QueryParam("secretName") String secretName,
                                          @QueryParam("vaultId") String vaultId,
                                          @QueryParam("versionNumber") Long versionNumber,
                                          @QueryParam("stage") String stage) {
        VaultSecretsService.Bundle bundle =
                service.getBundleByName(secretName, vaultId, versionNumber, stage);
        // No etag on GetSecretBundleByName responses.
        return Response.ok(bundleJson(bundle)).build();
    }

    @GET
    @Path("/{secretId}/versions")
    public Response listSecretBundleVersions(@PathParam("secretId") String secretId,
                                             @QueryParam("limit") Integer limit,
                                             @QueryParam("page") String page) {
        List<Map<String, Object>> versions = service.listSecretVersions(secretId).stream()
                .map(v -> bundleVersionJson(secretId, v)).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(versions, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    // ── Wire projections ───────────────────────────────────────────────────────

    private static Map<String, Object> bundleJson(VaultSecretsService.Bundle bundle) {
        StoredSecretVersion version = bundle.version();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("contentType", "BASE64");
        content.put("content", version.getContent());
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("secretId", bundle.secret().getId());
        json.put("versionNumber", version.getVersionNumber());
        json.put("timeCreated", version.getTimeCreated());
        json.put("secretBundleContent", content);
        json.put("stages", version.getStages());
        if (version.getName() != null) {
            json.put("versionName", version.getName());
        }
        return json;
    }

    private static Map<String, Object> bundleVersionJson(String secretId, StoredSecretVersion version) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("secretId", secretId);
        json.put("versionNumber", version.getVersionNumber());
        json.put("timeCreated", version.getTimeCreated());
        json.put("stages", version.getStages());
        if (version.getName() != null) {
            json.put("versionName", version.getName());
        }
        return json;
    }
}
