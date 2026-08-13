package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciPage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.oke.model.StoredNodePool;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * JAX-RS REST Controller for OCI Container Engine for Kubernetes (OKE) API (version 20180222).
 */
@Path("/20180222")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OkeController {

    private final OkeService service;
    private final OkeKubeconfigGenerator kubeconfigGenerator;
    private final WorkRequestService workRequests;

    @Inject
    public OkeController(OkeService service, OkeKubeconfigGenerator kubeconfigGenerator, WorkRequestService workRequests) {
        this.service = service;
        this.kubeconfigGenerator = kubeconfigGenerator;
        this.workRequests = workRequests;
    }

    // ── Clusters ─────────────────────────────────────────────────────────────

    @POST
    @Path("/clusters")
    public Response createCluster(CreateClusterDetails details) {
        CreateClusterDetails req = details != null ? details : new CreateClusterDetails();
        OkeService.CreateClusterResult result = service.createCluster(
            req.compartmentId,
            req.name,
            req.vcnId,
            req.kubernetesVersion,
            req.kmsKeyId,
            req.freeformTags,
            req.definedTags
        );

        return Response.status(Response.Status.ACCEPTED)
                .header("opc-work-request-id", result.workRequestId())
                .entity(result.cluster())
                .build();
    }

    @GET
    @Path("/clusters/{clusterId}")
    public Response getCluster(@PathParam("clusterId") String clusterId) {
        StoredOkeCluster cluster = service.getCluster(clusterId);
        return Response.ok(cluster).build();
    }

    @GET
    @Path("/clusters")
    public Response listClusters(@QueryParam("compartmentId") String compartmentId) {
        List<StoredOkeCluster> clusters = service.listClusters(compartmentId);
        return Response.ok(clusters).build();
    }

    @PUT
    @Path("/clusters/{clusterId}")
    public Response updateCluster(@PathParam("clusterId") String clusterId, UpdateClusterDetails details) {
        String workRequestId = service.updateCluster(
            clusterId,
            details != null ? details.name : null,
            details != null ? details.kubernetesVersion : null
        );
        return Response.status(Response.Status.ACCEPTED)
                .header("opc-work-request-id", workRequestId)
                .build();
    }

    @DELETE
    @Path("/clusters/{clusterId}")
    public Response deleteCluster(@PathParam("clusterId") String clusterId) {
        String workRequestId = service.deleteCluster(clusterId);
        return Response.status(Response.Status.ACCEPTED)
                .header("opc-work-request-id", workRequestId)
                .build();
    }

    // ── Kubeconfig ───────────────────────────────────────────────────────────

    @POST
    @Path("/clusters/{clusterId}/kubeconfig/content")
    @Produces("application/x-yaml")
    public Response createKubeconfig(@PathParam("clusterId") String clusterId,
                                     CreateKubeconfigDetails details) {
        StoredOkeCluster cluster = service.getCluster(clusterId);
        String tokenType = (details != null && details.tokenType != null) ? details.tokenType : "BASIC";
        String kubeconfigYaml = kubeconfigGenerator.generateKubeconfig(cluster, tokenType);
        return Response.ok(kubeconfigYaml)
                .header("Content-Type", "application/x-yaml")
                .build();
    }

    // ── Node Pools ────────────────────────────────────────────────────────────

    @POST
    @Path("/nodePools")
    public Response createNodePool(CreateNodePoolDetails details) {
        CreateNodePoolDetails req = details != null ? details : new CreateNodePoolDetails();
        OkeService.CreateNodePoolResult result = service.createNodePool(
            req.compartmentId,
            req.clusterId,
            req.name,
            req.kubernetesVersion,
            req.nodeShape,
            req.quantityPerSubnet,
            req.freeformTags,
            req.definedTags
        );

        return Response.status(Response.Status.ACCEPTED)
                .header("opc-work-request-id", result.workRequestId())
                .entity(result.nodePool())
                .build();
    }

    @GET
    @Path("/nodePools/{nodePoolId}")
    public Response getNodePool(@PathParam("nodePoolId") String nodePoolId) {
        StoredNodePool pool = service.getNodePool(nodePoolId);
        return Response.ok(pool).build();
    }

    @GET
    @Path("/nodePools")
    public Response listNodePools(@QueryParam("compartmentId") String compartmentId,
                                  @QueryParam("clusterId") String clusterId) {
        List<StoredNodePool> pools = service.listNodePools(compartmentId, clusterId);
        return Response.ok(pools).build();
    }

    @PUT
    @Path("/nodePools/{nodePoolId}")
    public Response updateNodePool(@PathParam("nodePoolId") String nodePoolId, UpdateNodePoolDetails details) {
        String workRequestId = service.updateNodePool(
            nodePoolId,
            details != null ? details.name : null,
            details != null ? details.kubernetesVersion : null,
            details != null ? details.quantityPerSubnet : null
        );
        return Response.status(Response.Status.ACCEPTED)
                .header("opc-work-request-id", workRequestId)
                .build();
    }

    @DELETE
    @Path("/nodePools/{nodePoolId}")
    public Response deleteNodePool(@PathParam("nodePoolId") String nodePoolId) {
        String workRequestId = service.deleteNodePool(nodePoolId);
        return Response.status(Response.Status.ACCEPTED)
                .header("opc-work-request-id", workRequestId)
                .build();
    }

    // ── Options ───────────────────────────────────────────────────────────────

    @GET
    @Path("/clusterOptions/{clusterOptionId}")
    public Response getClusterOptions(@PathParam("clusterOptionId") String clusterOptionId) {
        Map<String, Object> options = Map.of(
            "kubernetesVersions", List.of("v1.30.1", "v1.29.1", "v1.28.2")
        );
        return Response.ok(options).build();
    }

    @GET
    @Path("/nodePoolOptions/{nodePoolOptionId}")
    public Response getNodePoolOptions(@PathParam("nodePoolOptionId") String nodePoolOptionId) {
        Map<String, Object> options = Map.of(
            "shapes", List.of("VM.Standard.E4.Flex", "VM.Standard2.1", "VM.Standard3.Flex"),
            "kubernetesVersions", List.of("v1.30.1", "v1.29.1", "v1.28.2")
        );
        return Response.ok(options).build();
    }

    // ── Work Requests ─────────────────────────────────────────────────────────

    @GET
    @Path("/workRequests/{workRequestId}")
    public Response getWorkRequest(@PathParam("workRequestId") String workRequestId) {
        StoredWorkRequest wr = workRequests.get("oke", workRequestId);
        return Response.ok(wr.toWire()).build();
    }

    @GET
    @Path("/workRequests")
    public Response listWorkRequests(@QueryParam("compartmentId") String compartmentId,
                                      @QueryParam("limit") Integer limit,
                                      @QueryParam("page") String page) {
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(
                workRequests.list("oke", compartmentId).stream()
                        .map(StoredWorkRequest::toWire).toList(), limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @GET
    @Path("/workRequests/{workRequestId}/errors")
    public Response listWorkRequestErrors(@PathParam("workRequestId") String workRequestId) {
        workRequests.get("oke", workRequestId);
        return Response.ok(List.of()).build();
    }

    @GET
    @Path("/workRequests/{workRequestId}/logs")
    public Response listWorkRequestLogs(@PathParam("workRequestId") String workRequestId) {
        workRequests.get("oke", workRequestId);
        return Response.ok(List.of()).build();
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public static class CreateClusterDetails {
        public String compartmentId;
        public String name;
        public String vcnId;
        public String kubernetesVersion;
        public String kmsKeyId;
        public Map<String, String> freeformTags;
        public Map<String, Map<String, Object>> definedTags;
    }

    public static class UpdateClusterDetails {
        public String name;
        public String kubernetesVersion;
    }

    public static class CreateKubeconfigDetails {
        public String tokenType;
        public String expiration;
    }

    public static class CreateNodePoolDetails {
        public String compartmentId;
        public String clusterId;
        public String name;
        public String kubernetesVersion;
        public String nodeShape;
        public int quantityPerSubnet;
        public Map<String, String> freeformTags;
        public Map<String, Map<String, Object>> definedTags;
    }

    public static class UpdateNodePoolDetails {
        public String name;
        public String kubernetesVersion;
        public Integer quantityPerSubnet;
    }
}
