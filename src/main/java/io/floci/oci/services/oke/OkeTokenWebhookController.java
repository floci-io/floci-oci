package io.floci.oci.services.oke;

import io.floci.oci.core.auth.AuthContext;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Kubernetes token-authentication webhook for k3s-backed OKE clusters, meant for the k3s sidecar
 * and unauthenticated like every {@code /_floci-oci} endpoint: {@code kubectl} sends the {@code oci ce cluster generate-token} bearer token to the
 * k3s API server, which does not recognise it and POSTs a {@code TokenReview} here (configured in
 * {@link OkeClusterManager#webhookKubeconfig}). The tenancy and cluster are in the path because
 * client-go replaces a webhook URL's query.
 *
 * <p>This is floci-oci plumbing under {@code /_floci-oci}, not an OCI API, mirroring floci-aws's
 * {@code _floci/eks/clusters/{name}/token-webhook}.
 */
@Path("/_floci-oci/oke/token-webhook/{tenancyId}/{clusterId}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OkeTokenWebhookController {

    static final String DEFAULT_API_VERSION = "authentication.k8s.io/v1";

    private final OkeService service;
    private final OkeTokenValidator validator;

    @Inject
    public OkeTokenWebhookController(OkeService service, OkeTokenValidator validator) {
        this.service = service;
        this.validator = validator;
    }

    /**
     * Always answers 200: a rejection is {@code status.authenticated=false}, never an HTTP error,
     * which the API server would treat as the webhook being down. The response {@code apiVersion}
     * echoes the request's, because the API server cannot convert between TokenReview versions.
     */
    @POST
    public Response review(@PathParam("tenancyId") String tenancyId,
                           @PathParam("clusterId") String clusterId,
                           Map<String, Object> tokenReview) {
        String apiVersion = tokenReview != null && tokenReview.get("apiVersion") instanceof String version
                ? version
                : DEFAULT_API_VERSION;
        String token = tokenReview != null && tokenReview.get("spec") instanceof Map<?, ?> spec
                && spec.get("token") instanceof String value ? value : null;

        Optional<StoredOkeCluster> cluster = service.findCluster(tenancyId, clusterId)
                .filter(found -> tenancyId.equals(found.getTenancyId()));
        Optional<AuthContext> caller = cluster.flatMap(found -> validator.validate(token, found));
        if (caller.isEmpty()) {
            return Response.ok(Map.of(
                    "apiVersion", apiVersion,
                    "kind", "TokenReview",
                    "status", Map.of("authenticated", false))).build();
        }
        return Response.ok(Map.of(
                "apiVersion", apiVersion,
                "kind", "TokenReview",
                "status", Map.of(
                        "authenticated", true,
                        "user", Map.of(
                                "username", caller.get().userId(),
                                "uid", caller.get().userId(),
                                "groups", List.of("system:masters", "system:authenticated"))))).build();
    }
}
