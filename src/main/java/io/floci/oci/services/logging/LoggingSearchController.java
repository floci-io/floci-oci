package io.floci.oci.services.logging;

import io.floci.oci.core.common.OciPage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCI Logging Search, {@code /20190909/search} (oci-go-sdk loggingsearch/loggingsearch_client.go).
 * Answers a SearchResponse: {@code summary}, {@code results} (each {@code {"data": ...}}) and, with
 * {@code isReturnFieldInfo}, {@code fields}.
 */
@Path("/20190909")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LoggingSearchController {

    private final LoggingService service;

    @Inject
    public LoggingSearchController(LoggingService service) {
        this.service = service;
    }

    @POST
    @Path("/search")
    public Response searchLogs(@QueryParam("limit") Integer limit, @QueryParam("page") String page,
                               Map<String, Object> body) {
        List<Map<String, Object>> rows = service.search(body);
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(rows, limit, page);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> row : result.items()) {
            results.add(Map.of("data", row));
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map<String, Object> row : result.items()) {
            collectFields("", row, fields);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("resultCount", results.size());
        summary.put("fieldCount", fields.size());
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("summary", summary);
        json.put("results", results);
        if (body != null && Boolean.TRUE.equals(body.get("isReturnFieldInfo"))) {
            List<Map<String, Object>> info = new ArrayList<>();
            fields.forEach((name, type) -> info.add(Map.of("fieldName", name, "fieldType", type)));
            json.put("fields", info);
        }
        Response.ResponseBuilder builder = Response.ok(json);
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    /** Leaf fields by dotted name, typed as the FieldInfo enum: STRING, NUMBER, BOOLEAN or ARRAY. */
    private static void collectFields(String prefix, Map<?, ?> node, Map<String, String> fields) {
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String name = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> child) {
                collectFields(name, child, fields);
            } else if (value != null) {
                fields.putIfAbsent(name, value instanceof Number ? "NUMBER" : value instanceof Boolean ? "BOOLEAN"
                        : value instanceof Collection<?> ? "ARRAY" : "STRING");
            }
        }
    }
}
