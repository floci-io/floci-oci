package io.floci.oci.services.kms;

import io.floci.oci.core.common.OciPage;
import io.floci.oci.services.kms.model.StoredVault;
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
 * KMS vault control plane — {@code /20180608/vaults…}. There is no DELETE verb:
 * deletion is scheduled/cancelled via actions and expressed in {@code lifecycleState}.
 */
@Path("/20180608/vaults")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KmsVaultController {

    private final KmsService service;

    @Inject
    public KmsVaultController(KmsService service) {
        this.service = service;
    }

    @POST
    public Response createVault(Map<String, Object> body) {
        StoredVault v = service.createVault(str(body, "compartmentId"), str(body, "displayName"),
                str(body, "vaultType"), stringMap(body, "freeformTags"), definedTags(body));
        return Response.ok(vaultJson(v)).header("etag", v.getEtag()).build();
    }

    @GET
    @Path("/{vaultId}")
    public Response getVault(@PathParam("vaultId") String vaultId) {
        StoredVault v = service.getVault(vaultId);
        return Response.ok(vaultJson(v)).header("etag", v.getEtag()).build();
    }

    @GET
    public Response listVaults(@QueryParam("compartmentId") String compartmentId,
                               @QueryParam("limit") Integer limit,
                               @QueryParam("page") String page) {
        List<Map<String, Object>> summaries = service.listVaults(compartmentId).stream()
                .map(this::vaultSummaryJson).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(summaries, limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @PUT
    @Path("/{vaultId}")
    public Response updateVault(@PathParam("vaultId") String vaultId,
                                @HeaderParam("if-match") String ifMatch,
                                Map<String, Object> body) {
        StoredVault v = service.updateVault(vaultId, str(body, "displayName"),
                stringMap(body, "freeformTags"), definedTags(body), ifMatch);
        return Response.ok(vaultJson(v)).header("etag", v.getEtag()).build();
    }

    @POST
    @Path("/{vaultId}/actions/scheduleDeletion")
    public Response scheduleDeletion(@PathParam("vaultId") String vaultId,
                                     @HeaderParam("if-match") String ifMatch,
                                     Map<String, Object> body) {
        StoredVault v = service.scheduleVaultDeletion(vaultId, str(body, "timeOfDeletion"), ifMatch);
        return Response.ok(vaultJson(v)).header("etag", v.getEtag()).build();
    }

    @POST
    @Path("/{vaultId}/actions/cancelDeletion")
    public Response cancelDeletion(@PathParam("vaultId") String vaultId,
                                   @HeaderParam("if-match") String ifMatch) {
        StoredVault v = service.cancelVaultDeletion(vaultId, ifMatch);
        return Response.ok(vaultJson(v)).header("etag", v.getEtag()).build();
    }

    @POST
    @Path("/{vaultId}/actions/changeCompartment")
    public Response changeCompartment(@PathParam("vaultId") String vaultId,
                                      @HeaderParam("if-match") String ifMatch,
                                      Map<String, Object> body) {
        service.changeVaultCompartment(vaultId, str(body, "compartmentId"), ifMatch);
        StoredVault v = service.getVault(vaultId);
        // ChangeVaultCompartment has no body — only etag + opc-request-id.
        return Response.ok().header("etag", v.getEtag()).build();
    }

    // ── Wire projections ───────────────────────────────────────────────────────

    private Map<String, Object> vaultJson(StoredVault v) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("compartmentId", v.getCompartmentId());
        json.put("cryptoEndpoint", service.cryptoEndpoint(v.getId()));
        json.put("displayName", v.getDisplayName());
        json.put("id", v.getId());
        json.put("lifecycleState", v.getLifecycleState());
        json.put("managementEndpoint", service.managementEndpoint(v.getId()));
        json.put("timeCreated", v.getTimeCreated());
        json.put("vaultType", v.getVaultType());
        json.put("wrappingkeyId", v.getWrappingkeyId());
        json.put("isPrimary", true);
        if (v.getTimeOfDeletion() != null) {
            json.put("timeOfDeletion", v.getTimeOfDeletion());
        }
        if (v.getFreeformTags() != null) {
            json.put("freeformTags", v.getFreeformTags());
        }
        if (v.getDefinedTags() != null) {
            json.put("definedTags", v.getDefinedTags());
        }
        return json;
    }

    /** VaultSummary: same mandatory set minus wrappingkeyId, no timeOfDeletion. */
    private Map<String, Object> vaultSummaryJson(StoredVault v) {
        Map<String, Object> json = vaultJson(v);
        json.remove("wrappingkeyId");
        json.remove("timeOfDeletion");
        json.remove("isPrimary");
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
    private static Map<String, String> stringMap(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Map<?, ?> m ? (Map<String, String>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> definedTags(Map<String, Object> body) {
        return body != null && body.get("definedTags") instanceof Map<?, ?> m
                ? (Map<String, Map<String, Object>>) m : null;
    }
}
