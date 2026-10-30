package io.floci.oci.services.psql;

import io.floci.oci.services.identity.CompartmentValidator;
import io.floci.oci.services.psql.model.StoredConfiguration;
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

import java.util.Map;

/**
 * Custom configurations of OCI Database with PostgreSQL at {@code /20220915}. Synchronous, as in
 * the SDK: create, get and update return the Configuration with an {@code etag}; delete and
 * change-compartment return no body and no work request.
 */
@Path("/20220915")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PsqlConfigurationsController {

    private final PsqlConfigurationsService configurations;
    private final PsqlService dbSystems;
    private final CompartmentValidator compartments;

    @Inject
    public PsqlConfigurationsController(PsqlConfigurationsService configurations, PsqlService dbSystems,
                                        CompartmentValidator compartments) {
        this.configurations = configurations;
        this.dbSystems = dbSystems;
        this.compartments = compartments;
    }

    @POST
    @Path("/configurations")
    public Response createConfiguration(Map<String, Object> body) {
        Map<String, Object> details = body != null ? body : Map.of();
        try (CompartmentValidator.Use ignored = compartments.useInBody(PsqlRequests.string(details, "compartmentId"))) {
            return withEtag(configurations.createConfiguration(details));
        }
    }

    @GET
    @Path("/configurations/{configurationId}")
    public Response getConfiguration(@PathParam("configurationId") String configurationId) {
        return withEtag(configurations.getConfiguration(configurationId));
    }

    @GET
    @Path("/configurations")
    public Response listConfigurations(@QueryParam("compartmentId") String compartmentId,
                                       @QueryParam("lifecycleState") String lifecycleState,
                                       @QueryParam("configType") String configType,
                                       @QueryParam("displayName") String displayName,
                                       @QueryParam("dbVersion") String dbVersion,
                                       @QueryParam("shape") String shape,
                                       @QueryParam("configurationId") String configurationId,
                                       @QueryParam("instanceOcpuCount") Integer instanceOcpuCount,
                                       @QueryParam("instanceMemorySizeInGBs") Integer instanceMemorySizeInGBs,
                                       @QueryParam("sortBy") String sortBy,
                                       @QueryParam("sortOrder") String sortOrder,
                                       @QueryParam("limit") Integer limit,
                                       @QueryParam("page") String page) {
        if (compartmentId != null) {
            compartments.requireInQuery(compartmentId);
        }
        return PsqlRequests.collection(configurations.listConfigurations(compartmentId, lifecycleState, configType,
                displayName, dbVersion, shape, configurationId, instanceOcpuCount, instanceMemorySizeInGBs, sortBy,
                sortOrder).stream().map(StoredConfiguration::toSummaryWire).toList(), limit, page);
    }

    @PUT
    @Path("/configurations/{configurationId}")
    public Response updateConfiguration(@PathParam("configurationId") String configurationId,
                                        @HeaderParam("if-match") String ifMatch,
                                        Map<String, Object> body) {
        return withEtag(configurations.updateConfiguration(configurationId, body != null ? body : Map.of(), ifMatch));
    }

    @DELETE
    @Path("/configurations/{configurationId}")
    public Response deleteConfiguration(@PathParam("configurationId") String configurationId,
                                        @HeaderParam("if-match") String ifMatch) {
        configurations.deleteConfiguration(configurationId, ifMatch, dbSystems::usesConfiguration);
        return Response.noContent().build();
    }

    @POST
    @Path("/configurations/{configurationId}/actions/changeCompartment")
    public Response changeConfigurationCompartment(@PathParam("configurationId") String configurationId,
                                                   @HeaderParam("if-match") String ifMatch,
                                                   Map<String, Object> body) {
        String compartmentId = PsqlRequests.requireString(body != null ? body : Map.of(), "compartmentId");
        try (CompartmentValidator.Use ignored = compartments.useInBody(compartmentId)) {
            configurations.changeCompartment(configurationId, compartmentId, ifMatch);
        }
        return Response.noContent().build();
    }

    private static Response withEtag(StoredConfiguration configuration) {
        return Response.ok(configuration.toWire()).header("etag", configuration.getEtag()).build();
    }
}
