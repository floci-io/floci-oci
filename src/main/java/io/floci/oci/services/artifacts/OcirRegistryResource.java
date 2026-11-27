package io.floci.oci.services.artifacts;

import io.floci.oci.core.auth.SignatureAuthFilter;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.RequestContext;
import io.floci.oci.services.artifacts.model.StoredContainerRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The OCIR data plane: the Docker Registry HTTP API V2 on {@code <region>.ocir.<domain>/v2/},
 * proxied to the shared {@code registry:2} sidecar. Image references are
 * {@code <region>.ocir.<domain>/<namespace>/<repository>:<tag>} (Registry/Concepts/registryconcepts.htm);
 * the namespace names the tenancy and the host names the region, so the sidecar path is
 * {@code <regionCode>/<namespace>/<repository>}.
 *
 * <p>Requests are only served for a Host with an {@code ocir} label, such as
 * {@code iad.ocir.localhost.floci.io:4599} (resolves to 127.0.0.1, which Docker treats as an
 * insecure registry, so no TLS setup is needed). Login is a Bearer challenge answered by the token
 * endpoint; {@link OcirLogin} decides who gets a token and which tokens the registry accepts.
 */
@Path("/")
@ApplicationScoped
public class OcirRegistryResource {

    private static final Logger LOG = Logger.getLogger(OcirRegistryResource.class);

    /** {@code <key>.ocir.<domain>}, {@code <region>.ocir.<domain>} or {@code ocir.<region>.oci.<domain>}. */
    /**
     * The repository operations of the Registry HTTP API V2, each anchored at the end of the path.
     * The name group is greedy, so a repository component spelled {@code blobs}, {@code tags} or
     * {@code manifests} stays part of the name: only the final operation suffix is split off, as
     * Docker Distribution's router does.
     */
    private static final List<Pattern> REPOSITORY_OPERATIONS = List.of(
            Pattern.compile("^(.+)/(manifests)(/[^/]+)$"),
            Pattern.compile("^(.+)/(tags)(/list)$"),
            Pattern.compile("^(.+)/(blobs)(/[a-z0-9]+(?:[+._-][a-z0-9]+)*:[a-zA-Z0-9=_-]+)$"),
            Pattern.compile("^(.+)/(blobs)(/uploads(?:/[^/]*)?)$"));

    private static final Set<String> HOP_BY_HOP = Set.of("connection", "content-length", "expect", "host",
            "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding",
            "upgrade", "authorization");
    private static final Duration BACKEND_TIMEOUT = Duration.ofMinutes(5);

    private final ArtifactsService service;
    private final RequestContext requestContext;
    private final OcirLogin login;

    @Inject
    public OcirRegistryResource(ArtifactsService service, RequestContext requestContext, OcirLogin login) {
        this.service = service;
        this.requestContext = requestContext;
        this.login = login;
    }

    // ── Login ──────────────────────────────────────────────────────────────────

    @GET
    @Path(SignatureAuthFilter.OCIR_TOKEN_PATH)
    @Produces(MediaType.APPLICATION_JSON)
    public Response token(@Context HttpHeaders headers) {
        OcirLogin.Login outcome = login.login(headers.getHeaderString(HttpHeaders.AUTHORIZATION));
        if (outcome.isRejected()) {
            return registryError(401, "UNAUTHORIZED", outcome.rejection());
        }
        return Response.ok(Map.of("token", outcome.token(), "access_token", outcome.token(),
                "expires_in", OcirLogin.TOKEN_LIFETIME.toSeconds())).build();
    }

    // ── Registry API ───────────────────────────────────────────────────────────

    @GET
    @Path("v2{path: (/.*)?}")
    public Response get(@PathParam("path") String path, @Context HttpHeaders headers, @Context UriInfo uri) {
        return handle("GET", path, headers, uri, null);
    }

    @HEAD
    @Path("v2{path: (/.*)?}")
    public Response head(@PathParam("path") String path, @Context HttpHeaders headers, @Context UriInfo uri) {
        return handle("HEAD", path, headers, uri, null);
    }

    @DELETE
    @Path("v2{path: (/.*)?}")
    public Response delete(@PathParam("path") String path, @Context HttpHeaders headers, @Context UriInfo uri) {
        return handle("DELETE", path, headers, uri, null);
    }

    @POST
    @Path("v2{path: (/.*)?}")
    @Consumes(MediaType.WILDCARD)
    public Response post(@PathParam("path") String path, @Context HttpHeaders headers, @Context UriInfo uri,
                         InputStream body) {
        return handle("POST", path, headers, uri, body);
    }

    @PUT
    @Path("v2{path: (/.*)?}")
    @Consumes(MediaType.WILDCARD)
    public Response put(@PathParam("path") String path, @Context HttpHeaders headers, @Context UriInfo uri,
                        InputStream body) {
        return handle("PUT", path, headers, uri, body);
    }

    @PATCH
    @Path("v2{path: (/.*)?}")
    @Consumes(MediaType.WILDCARD)
    public Response patch(@PathParam("path") String path, @Context HttpHeaders headers, @Context UriInfo uri,
                          InputStream body) {
        return handle("PATCH", path, headers, uri, body);
    }

    private Response handle(String method, String rawPath, HttpHeaders headers, UriInfo uri, InputStream body) {
        String host = Optional.ofNullable(headers.getHeaderString("Host")).orElse("");
        Optional<String> region = OcirHosts.regionOf(host);
        if (region.isEmpty()) {
            throw OciException.notAuthorizedOrNotFound("No registry is served at " + host);
        }
        if (service.mock()) {
            return registryError(503, "UNAVAILABLE", "the OCIR data plane is disabled (artifacts mock mode)");
        }
        String authorization = headers.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return challenge(host, uri);
        }
        String bearer = authorization.substring(7).trim();
        if (!login.accepts(bearer)) {
            return challenge(host, uri);
        }
        // The raw request path, not the path parameter: JAX-RS drops a trailing slash, and the
        // registry answers "blobs/uploads" (no slash) with a redirect instead of an upload session.
        String requestPath = uri.getRequestUri().getRawPath();
        String path = requestPath.substring(requestPath.indexOf("/v2") + 3).replaceFirst("^/+", "");
        if (path.isEmpty()) {
            return Response.ok("{}", MediaType.APPLICATION_JSON)
                    .header("Docker-Distribution-API-Version", "registry/2.0").build();
        }
        Optional<RepositoryOperation> parsed = RepositoryOperation.parse(path);
        if (parsed.isEmpty()) {
            return registryError(404, "UNSUPPORTED", "floci-oci serves repository operations only: " + path);
        }
        RepositoryOperation operation = parsed.get();
        String fullName = operation.namespace() + "/" + operation.repository();
        String namespace = operation.namespace();
        String repositoryName = operation.repository();
        Optional<String> tenancy = service.tenancyOfNamespace(namespace);
        if (tenancy.isEmpty()) {
            return registryError(404, "NAME_UNKNOWN", "namespace " + namespace + " is not known to this registry");
        }
        if (!login.allows(bearer, tenancy.get())) {
            return registryError(403, "DENIED", "the login does not grant access to namespace " + namespace);
        }
        requestContext.setTenancyId(tenancy.get());
        requestContext.setRegion(region.get());

        boolean write = !"GET".equals(method) && !"HEAD".equals(method);
        Optional<StoredContainerRepository> repository = write
                ? service.repositoryForPush(repositoryName) : service.findByName(repositoryName);
        if (repository.isEmpty()) {
            return registryError(404, "NAME_UNKNOWN", "repository " + namespace + "/" + repositoryName
                    + " does not exist; create it first or enable isRepositoryCreatedOnFirstPush");
        }
        StoredContainerRepository repo = repository.get();
        boolean manifestPut = "PUT".equals(method) && "manifests".equals(operation.kind());
        if (manifestPut && repo.isImmutable()) {
            return registryError(403, "DENIED", "repository " + repositoryName + " is immutable");
        }

        ArtifactsRegistryManager registry = service.registry();
        registry.ensureReady();
        String backendName = service.backendRepository(repo);
        String backendPath = "/v2/" + backendName + "/" + operation.kind() + operation.suffix();
        String query = backendQuery(method, operation, uri.getRequestUri().getRawQuery(), bearer);
        URI target = URI.create("http://" + registry.endpoint() + backendPath + (query == null ? "" : "?" + query));
        try {
            Response response = forward(registry, method, target, headers, body, backendName, fullName);
            if (manifestPut && response.getStatus() / 100 == 2) {
                service.recordPush(repo.getId(), response.getHeaderString("Docker-Content-Digest"));
            }
            return response;
        } catch (Exception e) {
            LOG.warnv("OCIR request to {0} failed: {1}", target, e.getMessage());
            return registryError(502, "UNAVAILABLE", "the registry is unavailable: " + e.getMessage());
        }
    }

    /**
     * The query sent to the sidecar. A cross-repository blob mount names its source as an OCIR
     * repository, {@code from=<namespace>/<repository>}, which is translated to the sidecar path the
     * way the request path is, when the login may read that namespace. Otherwise the mount is left
     * out and the sidecar opens a plain upload session (202 with a Location), the fallback the
     * Registry HTTP API V2 gives a mount the registry will not perform, which docker push follows.
     */
    private String backendQuery(String method, RepositoryOperation operation, String rawQuery, String bearer) {
        if (rawQuery == null || !"POST".equals(method) || !"blobs".equals(operation.kind())) {
            return rawQuery;
        }
        return mountQuery(rawQuery, from -> mountSource(from, bearer));
    }

    private Optional<String> mountSource(String from, String bearer) {
        int slash = from.indexOf('/');
        if (slash <= 0 || slash == from.length() - 1) {
            return Optional.empty();
        }
        String namespace = from.substring(0, slash);
        Optional<String> tenancy = service.tenancyOfNamespace(namespace);
        if (tenancy.isEmpty() || !login.allows(bearer, tenancy.get())) {
            return Optional.empty();
        }
        return Optional.of(service.backendRepository(namespace, from.substring(slash + 1)));
    }

    /**
     * {@code rawQuery} with its {@code mount} and {@code from} parameters replaced: kept with
     * {@code from} set to the backend repository {@code source} maps it to, or both left out when it
     * maps to none. As Docker Distribution reads them, only the first of each counts.
     */
    static String mountQuery(String rawQuery, Function<String, Optional<String>> source) {
        List<String> kept = new ArrayList<>();
        String mount = null;
        String from = null;
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String name = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
            if ("mount".equals(name)) {
                mount = mount == null ? value : mount;
            } else if ("from".equals(name)) {
                from = from == null ? value : from;
            } else {
                kept.add(pair);
            }
        }
        if (mount == null && from == null) {
            return rawQuery;
        }
        Optional<String> backend = mount == null || from == null ? Optional.empty() : source.apply(from);
        if (backend.isPresent()) {
            kept.add("mount=" + encode(mount));
            kept.add("from=" + encode(backend.get()));
        }
        return kept.isEmpty() ? null : String.join("&", kept);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Response forward(ArtifactsRegistryManager registry, String method, URI target, HttpHeaders headers,
                             InputStream body, String backendName, String clientName) throws Exception {
        HttpRequest.Builder outgoing = HttpRequest.newBuilder(target).timeout(BACKEND_TIMEOUT)
                .method(method, bodyPublisher(headers, body));
        headers.getRequestHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> outgoing.header(name, value));
            }
        });
        boolean tagsList = target.getPath().endsWith("/tags/list");
        if ("HEAD".equals(method)) {
            return response(registry.httpClient().send(outgoing.build(), HttpResponse.BodyHandlers.discarding()),
                    null, true, backendName, clientName);
        }
        if (tagsList) {
            HttpResponse<String> backend = registry.httpClient().send(outgoing.build(),
                    HttpResponse.BodyHandlers.ofString());
            return response(backend, backend.body().replace("\"" + backendName + "\"", "\"" + clientName + "\""),
                    false, backendName, clientName);
        }
        HttpResponse<InputStream> backend = registry.httpClient().send(outgoing.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        return response(backend, backend.body(), false, backendName, clientName);
    }

    /** Streams the client's body through with the length it declared; a layer is never held in memory. */
    private static HttpRequest.BodyPublisher bodyPublisher(HttpHeaders headers, InputStream body) {
        if (body == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        String declared = headers.getHeaderString("Content-Length");
        if (declared != null && !declared.isBlank()) {
            long length = Long.parseLong(declared.trim());
            if (length == 0) {
                return HttpRequest.BodyPublishers.noBody();
            }
            return HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> body),
                    length);
        }
        return HttpRequest.BodyPublishers.ofInputStream(() -> body);
    }

    private static Response response(HttpResponse<?> backend, Object entity, boolean keepContentLength,
                                     String backendName, String clientName) {
        Response.ResponseBuilder response = Response.status(backend.statusCode()).entity(entity);
        backend.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if ("content-length".equals(lower)) {
                if (keepContentLength) {
                    values.forEach(value -> response.header(name, value));
                }
                return;
            }
            if (HOP_BY_HOP.contains(lower)) {
                return;
            }
            if ("location".equals(lower)) {
                values.forEach(value -> response.header(name, clientLocation(value, backendName, clientName)));
                return;
            }
            values.forEach(value -> response.header(name, value));
        });
        return response.build();
    }

    /** An upload session {@code Location} with the sidecar's authority and region prefix removed. */
    static String clientLocation(String location, String backendName, String clientName) {
        String path = location;
        int schemeEnd = path.indexOf("://");
        if (schemeEnd >= 0) {
            int authorityEnd = path.indexOf('/', schemeEnd + 3);
            path = authorityEnd < 0 ? "/" : path.substring(authorityEnd);
        }
        String backendPrefix = "/v2/" + backendName + "/";
        return path.startsWith(backendPrefix) ? "/v2/" + clientName + "/" + path.substring(backendPrefix.length()) : path;
    }

    private static Response challenge(String host, UriInfo uri) {
        String scheme = uri.getRequestUri().getScheme() == null ? "http" : uri.getRequestUri().getScheme();
        String realm = scheme + "://" + host + "/" + SignatureAuthFilter.OCIR_TOKEN_PATH;
        return Response.status(401)
                .header("WWW-Authenticate", "Bearer realm=\"" + realm + "\",service=\"" + host + "\"")
                .header("Docker-Distribution-API-Version", "registry/2.0")
                .type(MediaType.APPLICATION_JSON)
                .entity("{\"errors\":[{\"code\":\"UNAUTHORIZED\",\"message\":\"authentication required\"}]}")
                .build();
    }

    private static Response registryError(int status, String code, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("errors", List.of(Map.of("code", code, "message", message))))
                .build();
    }

    /**
     * A repository operation: {@code <namespace>/<repository>/<kind><suffix>}, where {@code kind} is
     * {@code manifests}, {@code blobs} or {@code tags} and {@code suffix} keeps its leading slash.
     */
    record RepositoryOperation(String namespace, String repository, String kind, String suffix) {

        static Optional<RepositoryOperation> parse(String path) {
            for (Pattern pattern : REPOSITORY_OPERATIONS) {
                Matcher m = pattern.matcher(path);
                if (m.matches()) {
                    String fullName = m.group(1);
                    int slash = fullName.indexOf('/');
                    if (slash <= 0 || slash == fullName.length() - 1) {
                        return Optional.empty();
                    }
                    return Optional.of(new RepositoryOperation(fullName.substring(0, slash),
                            fullName.substring(slash + 1), m.group(2), m.group(3)));
                }
            }
            return Optional.empty();
        }
    }
}
