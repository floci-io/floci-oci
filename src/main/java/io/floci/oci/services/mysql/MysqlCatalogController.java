package io.floci.oci.services.mysql;

import io.floci.oci.core.common.OciException;
import io.floci.oci.services.identity.CompartmentValidator;
import io.floci.oci.services.mysql.model.StoredMysqlConfiguration;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The MySQL HeatWave catalog: shapes, versions and configurations (oci-go-sdk/mysql
 * MysqlaasClient). Lists are bare JSON arrays; ListShapes and ListVersions are not paged.
 */
@Path(MysqlService.API_PREFIX)
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MysqlCatalogController {

    private static final Set<String> SUPPORTED_FOR = Set.of("DBSYSTEM", "HEATWAVECLUSTER");

    private final MysqlConfigurationsService configurations;
    private final MysqlService dbSystems;
    private final CompartmentValidator compartments;

    @Inject
    public MysqlCatalogController(MysqlConfigurationsService configurations, MysqlService dbSystems,
                                  CompartmentValidator compartments) {
        this.configurations = configurations;
        this.dbSystems = dbSystems;
        this.compartments = compartments;
    }

    @GET
    @Path("/shapes")
    public Response listShapes(@QueryParam("compartmentId") String compartmentId,
                               @QueryParam("isSupportedFor") List<String> isSupportedFor,
                               @QueryParam("availabilityDomain") String availabilityDomain,
                               @QueryParam("name") String name) {
        requireCompartment(compartmentId);
        for (String use : isSupportedFor) {
            MysqlRequests.requireEnum(use, SUPPORTED_FOR, "isSupportedFor");
        }
        return Response.ok(MysqlCatalog.SHAPES.stream()
                .filter(shape -> name == null || name.equals(shape.name()))
                .filter(shape -> !isSupportedFor.contains("HEATWAVECLUSTER") || shape.heatWave())
                .map(MysqlCatalog.Shape::toWire)
                .toList()).build();
    }

    @GET
    @Path("/versions")
    public Response listVersions(@QueryParam("compartmentId") String compartmentId) {
        requireCompartment(compartmentId);
        return Response.ok(MysqlCatalog.versions()).build();
    }

    // ── Configurations ─────────────────────────────────────────────────────────

    @POST
    @Path("/configurations")
    public Response createConfiguration(Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        String compartmentId = MysqlRequests.string(details, "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            StoredMysqlConfiguration configuration = configurations.createConfiguration(details);
            return Response.ok(configuration.toWire())
                    .header("etag", configuration.getEtag())
                    .header("location", MysqlService.API_PREFIX + "/configurations/" + configuration.getId())
                    .build();
        }
    }

    @GET
    @Path("/configurations/{configurationId}")
    public Response getConfiguration(@PathParam("configurationId") String configurationId) {
        Map<String, Object> configuration = configurations.configurationWire(configurationId);
        Response.ResponseBuilder response = Response.ok(configuration);
        Optional<String> etag = configurations.etag(configurationId);
        etag.ifPresent(value -> response.header("etag", value));
        return response.build();
    }

    @GET
    @Path("/configurations")
    public Response listConfigurations(@QueryParam("compartmentId") String compartmentId,
                                       @QueryParam("configurationId") String configurationId,
                                       @QueryParam("lifecycleState") String lifecycleState,
                                       @QueryParam("type") List<String> types,
                                       @QueryParam("displayName") String displayName,
                                       @QueryParam("shapeName") String shapeName,
                                       @QueryParam("sortBy") String sortBy,
                                       @QueryParam("sortOrder") String sortOrder,
                                       @QueryParam("limit") Integer limit,
                                       @QueryParam("page") String page) {
        requireCompartment(compartmentId);
        return MysqlRequests.list(configurations.listConfigurations(compartmentId, configurationId, lifecycleState,
                types, displayName, shapeName, sortBy, sortOrder), limit, page);
    }

    @PUT
    @Path("/configurations/{configurationId}")
    public Response updateConfiguration(@PathParam("configurationId") String configurationId,
                                        @HeaderParam("if-match") String ifMatch,
                                        Map<String, Object> body) {
        StoredMysqlConfiguration configuration = configurations.updateConfiguration(configurationId,
                body != null ? body : Map.of(), ifMatch);
        return Response.ok(configuration.toWire()).header("etag", configuration.getEtag()).build();
    }

    @DELETE
    @Path("/configurations/{configurationId}")
    public Response deleteConfiguration(@PathParam("configurationId") String configurationId,
                                        @HeaderParam("if-match") String ifMatch) {
        configurations.deleteConfiguration(configurationId, ifMatch, dbSystems::usesConfiguration);
        return Response.noContent().build();
    }

    /** Every MySQL list takes a mandatory compartmentId, which must exist. */
    private void requireCompartment(String compartmentId) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: compartmentId");
        }
        compartments.requireInQuery(compartmentId);
    }
}
