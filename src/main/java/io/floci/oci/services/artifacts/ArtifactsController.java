package io.floci.oci.services.artifacts;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.services.artifacts.model.StoredContainerRepository;
import io.floci.oci.services.identity.CompartmentValidator;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCI Artifacts container management plane, {@code /20160918/container/...}
 * (oci-go-sdk/artifacts/artifacts_client.go). Synchronous, no work requests. Lists return
 * collection objects ({@code ContainerRepositoryCollection}, {@code ContainerImageCollection}),
 * not bare arrays, paginated with {@code opc-next-page}. Shares the {@code /20160918} root with
 * Identity; every path here is under {@code /container}.
 */
@Path("/20160918/container")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ArtifactsController {

    private final ArtifactsService service;
    private final CompartmentValidator compartments;

    @Inject
    public ArtifactsController(ArtifactsService service, CompartmentValidator compartments) {
        this.service = service;
        this.compartments = compartments;
    }

    // ── Repositories ───────────────────────────────────────────────────────────

    @POST
    @Path("/repositories")
    public Response createRepository(Map<String, Object> body) {
        try (CompartmentValidator.Use ignored = compartments.useInBody(ArtifactsService.str(body, "compartmentId"))) {
            StoredContainerRepository r = service.createRepository(body);
            return Response.ok(repositoryJson(r)).header("etag", r.getEtag()).build();
        }
    }

    @GET
    @Path("/repositories/{repositoryId}")
    public Response getRepository(@PathParam("repositoryId") String repositoryId) {
        StoredContainerRepository r = service.getRepository(repositoryId);
        return Response.ok(repositoryJson(r)).header("etag", r.getEtag()).build();
    }

    @GET
    @Path("/repositories")
    public Response listRepositories(@QueryParam("compartmentId") String compartmentId,
                                     @QueryParam("compartmentIdInSubtree") Boolean inSubtree,
                                     @QueryParam("repositoryId") String repositoryId,
                                     @QueryParam("displayName") String displayName,
                                     @QueryParam("isPublic") Boolean isPublic,
                                     @QueryParam("lifecycleState") String lifecycleState,
                                     @QueryParam("sortBy") String sortBy,
                                     @QueryParam("sortOrder") String sortOrder,
                                     @QueryParam("limit") Integer limit,
                                     @QueryParam("page") String page) {
        requireCompartment(compartmentId);
        List<StoredContainerRepository> all = service.listRepositories(compartmentId, Boolean.TRUE.equals(inSubtree),
                repositoryId, displayName, isPublic, lifecycleState, sortBy, sortOrder);
        OciPage.Page<StoredContainerRepository> result = OciPage.paginate(all, limit, page);
        long imageCount = 0;
        long layerCount = 0;
        long layersSize = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (StoredContainerRepository r : result.items()) {
            ArtifactsService.RepositoryStats stats = service.stats(r);
            imageCount += stats.imageCount();
            layerCount += stats.layerCount();
            layersSize += stats.layersSizeInBytes();
            items.add(repositorySummaryJson(r, stats));
        }
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("imageCount", imageCount);
        collection.put("items", items);
        collection.put("layerCount", layerCount);
        collection.put("layersSizeInBytes", layersSize);
        collection.put("remainingItemsCount", all.size() - result.items().size());
        collection.put("repositoryCount", all.size());
        return withNextPage(Response.ok(collection), result).build();
    }

    @PUT
    @Path("/repositories/{repositoryId}")
    public Response updateRepository(@PathParam("repositoryId") String repositoryId,
                                     @HeaderParam("if-match") String ifMatch, Map<String, Object> body) {
        StoredContainerRepository r = service.updateRepository(repositoryId, body, ifMatch);
        return Response.ok(repositoryJson(r)).header("etag", r.getEtag()).build();
    }

    @DELETE
    @Path("/repositories/{repositoryId}")
    public Response deleteRepository(@PathParam("repositoryId") String repositoryId,
                                     @HeaderParam("if-match") String ifMatch) {
        service.deleteRepository(repositoryId, ifMatch);
        return Response.noContent().build();
    }

    @POST
    @Path("/repositories/{repositoryId}/actions/changeCompartment")
    public Response changeCompartment(@PathParam("repositoryId") String repositoryId,
                                      @HeaderParam("if-match") String ifMatch, Map<String, Object> body) {
        String compartmentId = ArtifactsService.str(body, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            service.changeCompartment(repositoryId, compartmentId, ifMatch);
            return Response.ok().build();
        }
    }

    // ── Images ─────────────────────────────────────────────────────────────────

    @GET
    @Path("/images")
    public Response listImages(@QueryParam("compartmentId") String compartmentId,
                               @QueryParam("compartmentIdInSubtree") Boolean inSubtree,
                               @QueryParam("repositoryId") String repositoryId,
                               @QueryParam("repositoryName") String repositoryName,
                               @QueryParam("displayName") String displayName,
                               @QueryParam("imageId") String imageId,
                               @QueryParam("version") String version,
                               @QueryParam("imageDigest") String imageDigest,
                               @QueryParam("isVersioned") Boolean isVersioned,
                               @QueryParam("limit") Integer limit,
                               @QueryParam("page") String page) {
        requireCompartment(compartmentId);
        List<Map<String, Object>> summaries = service.listImages(compartmentId, Boolean.TRUE.equals(inSubtree),
                        repositoryId, repositoryName, displayName, imageId, version, imageDigest, isVersioned)
                .stream().map(ArtifactsController::imageSummaryJson).toList();
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(summaries, limit, page);
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("items", result.items());
        collection.put("remainingItemsCount", summaries.size() - result.items().size());
        return withNextPage(Response.ok(collection), result).build();
    }

    @GET
    @Path("/images/{imageId}")
    public Response getImage(@PathParam("imageId") String imageId) {
        return Response.ok(service.getImage(imageId)).build();
    }

    @DELETE
    @Path("/images/{imageId}")
    public Response deleteImage(@PathParam("imageId") String imageId) {
        service.deleteImage(imageId);
        return Response.noContent().build();
    }

    /** The registry API cannot remove one tag from a manifest, so this action is not emulated. */
    @POST
    @Path("/images/{imageId}/actions/removeVersion")
    public Response removeVersion(@PathParam("imageId") String imageId) {
        throw OciException.notImplemented("RemoveContainerVersion is not implemented by floci-oci");
    }

    @POST
    @Path("/images/{imageId}/actions/restore")
    public Response restore(@PathParam("imageId") String imageId) {
        throw OciException.notImplemented("RestoreContainerImage is not implemented by floci-oci");
    }

    // ── Configuration ──────────────────────────────────────────────────────────

    @GET
    @Path("/configuration")
    public Response getConfiguration(@QueryParam("compartmentId") String compartmentId) {
        requireCompartment(compartmentId);
        return Response.ok(service.configuration()).header("etag", configurationEtag()).build();
    }

    @PUT
    @Path("/configuration")
    public Response updateConfiguration(@QueryParam("compartmentId") String compartmentId,
                                        Map<String, Object> body) {
        requireCompartment(compartmentId);
        return Response.ok(service.updateConfiguration(body)).header("etag", configurationEtag()).build();
    }

    // ── Wire projections ───────────────────────────────────────────────────────

    private String configurationEtag() {
        return Integer.toHexString(service.configuration().hashCode());
    }

    private void requireCompartment(String compartmentId) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("compartmentId is required");
        }
        compartments.requireInQuery(compartmentId);
    }

    private Map<String, Object> repositoryJson(StoredContainerRepository r) {
        ArtifactsService.RepositoryStats stats = service.stats(r);
        Map<String, Object> json = repositorySummaryJson(r, stats);
        json.put("createdBy", r.getCreatedBy());
        json.put("isImmutable", r.isImmutable());
        if (r.getReadme() != null) {
            json.put("readme", r.getReadme());
        }
        if (r.getTimeLastPushed() != null) {
            json.put("timeLastPushed", r.getTimeLastPushed());
        }
        json.put("multiRegionPeers", List.of());
        return json;
    }

    private static Map<String, Object> repositorySummaryJson(StoredContainerRepository r,
                                                             ArtifactsService.RepositoryStats stats) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("compartmentId", r.getCompartmentId());
        json.put("displayName", r.getDisplayName());
        json.put("id", r.getId());
        json.put("imageCount", stats.imageCount());
        json.put("isPublic", r.isPublicRepository());
        json.put("layerCount", stats.layerCount());
        json.put("layersSizeInBytes", stats.layersSizeInBytes());
        json.put("lifecycleState", r.getLifecycleState());
        json.put("timeCreated", r.getTimeCreated());
        json.put("billableSizeInGBs", stats.billableSizeInGBs());
        json.put("namespace", r.getNamespace());
        json.put("repositoryType", "STANDARD");
        json.put("isTopLevel", false);
        json.put("freeformTags", r.getFreeformTags());
        json.put("definedTags", r.getDefinedTags());
        json.put("systemTags", Map.of());
        return json;
    }

    private static Map<String, Object> imageSummaryJson(Map<String, Object> image) {
        Map<String, Object> json = new LinkedHashMap<>();
        for (String key : List.of("compartmentId", "digest", "displayName", "id", "lifecycleState",
                "repositoryId", "repositoryName", "timeCreated", "version", "freeformTags", "definedTags",
                "systemTags")) {
            if (image.get(key) != null) {
                json.put(key, image.get(key));
            }
        }
        return json;
    }

    private static <T> Response.ResponseBuilder withNextPage(Response.ResponseBuilder builder, OciPage.Page<T> page) {
        if (page.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, page.nextPage());
        }
        return builder;
    }
}
