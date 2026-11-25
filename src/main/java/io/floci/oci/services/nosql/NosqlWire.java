package io.floci.oci.services.nosql;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.services.nosql.model.StoredNosqlTable;
import io.floci.oci.services.nosql.model.StoredNosqlTable.Column;
import io.floci.oci.services.nosql.model.StoredNosqlTable.Index;
import io.floci.oci.services.nosql.model.StoredNosqlTable.IndexKey;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Request-body readers and the oci-go-sdk {@code nosql} wire shapes both controllers share. */
final class NosqlWire {

    private NosqlWire() {
    }

    static Response accepted(NosqlService.WorkRequestOutcome outcome) {
        return Response.accepted().header("opc-work-request-id", outcome.workRequestId()).build();
    }

    static <T> Response page(List<T> all, Integer limit, String page, Map<String, Object> extra) {
        OciPage.Page<T> result = OciPage.paginate(all, limit, page);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items());
        body.putAll(extra);
        Response.ResponseBuilder builder = Response.ok(body);
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    /** Table, per {@code table.go}. */
    static Map<String, Object> table(StoredNosqlTable t) {
        Map<String, Object> json = summaryFields(t);
        json.put("schema", schema(t));
        json.put("ddlStatement", t.getDdlStatement());
        json.put("replicas", List.of());
        return json;
    }

    /** TableSummary, per {@code table_summary.go}. */
    static Map<String, Object> tableSummary(StoredNosqlTable t) {
        return summaryFields(t);
    }

    private static Map<String, Object> summaryFields(StoredNosqlTable t) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", t.getId());
        json.put("compartmentId", t.getCompartmentId());
        json.put("name", t.getName());
        json.put("timeCreated", t.getTimeCreated());
        json.put("timeUpdated", t.getTimeUpdated());
        json.put("tableLimits", limits(t.getTableLimits()));
        json.put("lifecycleState", t.getLifecycleState());
        json.put("isAutoReclaimable", t.isAutoReclaimable());
        json.put("schemaState", t.getSchemaState());
        json.put("isMultiRegion", false);
        json.put("freeformTags", t.getFreeformTags() != null ? t.getFreeformTags() : Map.of());
        json.put("definedTags", t.getDefinedTags() != null ? t.getDefinedTags() : Map.of());
        json.put("systemTags", Map.of());
        return json;
    }

    private static Map<String, Object> limits(StoredNosqlTable.Limits limits) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("maxReadUnits", limits.getMaxReadUnits());
        json.put("maxWriteUnits", limits.getMaxWriteUnits());
        json.put("maxStorageInGBs", limits.getMaxStorageInGBs());
        json.put("capacityMode", limits.getCapacityMode());
        return json;
    }

    private static Map<String, Object> schema(StoredNosqlTable t) {
        Map<String, Object> schema = new LinkedHashMap<>();
        List<Map<String, Object>> columns = new ArrayList<>();
        for (Column c : t.getColumns()) {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", c.getName());
            column.put("type", c.getType());
            column.put("isNullable", c.isNullable());
            if (c.getDefaultValue() != null) {
                column.put("defaultValue", c.getDefaultValue());
            }
            column.put("isAsUuid", c.isAsUuid());
            column.put("isGenerated", c.isUuidGenerated());
            columns.add(column);
        }
        schema.put("columns", columns);
        schema.put("primaryKey", t.getPrimaryKey());
        schema.put("shardKey", t.getShardKey());
        schema.put("ttl", NosqlService.schemaTtlDays(t));
        if (t.getIdentity() != null) {
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("columnName", t.getIdentity().getColumnName());
            identity.put("isAlways", t.getIdentity().isAlways());
            identity.put("isNull", t.getIdentity().isOnNull());
            schema.put("identity", identity);
        }
        return schema;
    }

    /** Index, per {@code index.go}. */
    static Map<String, Object> index(StoredNosqlTable t, Index index) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", index.getName());
        json.put("compartmentId", t.getCompartmentId());
        json.put("tableName", t.getName());
        json.put("tableId", t.getId());
        json.put("keys", keys(index));
        json.put("lifecycleState", index.getLifecycleState());
        return json;
    }

    /** IndexSummary, per {@code index_summary.go}. */
    static Map<String, Object> indexSummary(Index index) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", index.getName());
        json.put("keys", keys(index));
        json.put("lifecycleState", index.getLifecycleState());
        return json;
    }

    private static List<Map<String, Object>> keys(Index index) {
        List<Map<String, Object>> keys = new ArrayList<>();
        for (IndexKey key : index.getKeys()) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("columnName", key.getColumnName());
            if (key.getJsonPath() != null) {
                json.put("jsonPath", key.getJsonPath());
            }
            if (key.getJsonFieldType() != null) {
                json.put("jsonFieldType", key.getJsonFieldType());
            }
            keys.add(json);
        }
        return keys;
    }

    static Map<String, Object> usage(int read, int write) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("readUnitsConsumed", read);
        usage.put("writeUnitsConsumed", write);
        return usage;
    }

    /** TableLimits from a request body; ON_DEMAND tables report zero read and write units. */
    static StoredNosqlTable.Limits limits(Map<String, Object> body) {
        if (body == null || !(body.get("tableLimits") instanceof Map<?, ?> raw)) {
            return null;
        }
        String mode = raw.get("capacityMode") instanceof String s && !s.isBlank() ? s : "PROVISIONED";
        if (!"PROVISIONED".equals(mode) && !"ON_DEMAND".equals(mode)) {
            throw OciException.invalidParameter("tableLimits.capacityMode must be PROVISIONED or ON_DEMAND");
        }
        Integer storage = raw.get("maxStorageInGBs") instanceof Number n ? n.intValue() : null;
        if (storage == null) {
            throw OciException.missingParameter("tableLimits.maxStorageInGBs is required");
        }
        if ("ON_DEMAND".equals(mode)) {
            return new StoredNosqlTable.Limits(0, 0, storage, mode);
        }
        if (!(raw.get("maxReadUnits") instanceof Number read) || !(raw.get("maxWriteUnits") instanceof Number write)) {
            throw OciException.missingParameter(
                    "tableLimits.maxReadUnits and tableLimits.maxWriteUnits are required for PROVISIONED tables");
        }
        if (read.intValue() < 0 || write.intValue() < 0 || storage < 0) {
            throw OciException.invalidParameter("tableLimits values must not be negative");
        }
        return new StoredNosqlTable.Limits(read.intValue(), write.intValue(), storage, mode);
    }

    static String str(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        return body.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    static Boolean bool(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Boolean b ? b : null;
    }

    static Integer integer(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Number n ? n.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> stringMap(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof Map<?, ?> m ? (Map<String, String>) m : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> definedTags(Map<String, Object> body) {
        return body != null && body.get("definedTags") instanceof Map<?, ?> m
                ? (Map<String, Map<String, Object>>) m : null;
    }

    static List<IndexKey> indexKeys(Map<String, Object> body) {
        if (body == null || !(body.get("keys") instanceof List<?> list)) {
            return null;
        }
        List<IndexKey> keys = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> key)) {
                throw OciException.invalidParameter("keys must be a list of IndexKey objects");
            }
            keys.add(new IndexKey(text(key.get("columnName")), text(key.get("jsonPath")),
                    text(key.get("jsonFieldType"))));
        }
        return keys;
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
