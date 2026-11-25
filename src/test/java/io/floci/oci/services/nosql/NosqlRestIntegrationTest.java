package io.floci.oci.services.nosql;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class NosqlRestIntegrationTest {

    private static final String API = "/20190828";
    private static final String TERRAFORM_DDL = "CREATE TABLE IF NOT EXISTS test_table(id INTEGER GENERATED ALWAYS"
            + " AS IDENTITY, name STRING, age STRING, info JSON, guid STRING AS UUID, PRIMARY KEY(SHARD(id)))";

    private static String compartment;

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itnosqlcompartment");
        }
    }

    /** Creates a table through the wire contract: 202, no body, then the work request's TABLE resource. */
    private String createTable(String name, String ddl, Map<String, Object> limits) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("compartmentId", compartment);
        body.put("ddlStatement", ddl);
        if (limits != null) {
            body.put("tableLimits", limits);
        }
        String workRequestId = given().contentType("application/json").body(body)
            .when().post(API + "/tables")
            .then()
                .statusCode(202)
                .header("opc-request-id", notNullValue())
                .header("opc-work-request-id", startsWith("ocid1."))
                .body(emptyOrNullString())
                .extract().header("opc-work-request-id");

        return given()
            .when().get(API + "/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("operationType", equalTo("CREATE_TABLE"))
                .body("status", equalTo("SUCCEEDED"))
                .body("timeFinished", notNullValue())
                .body("resources[0].entityType", equalTo("TABLE"))
                .body("resources[0].actionType", equalTo("CREATED"))
                .extract().path("resources[0].identifier");
    }

    private void putRow(String table, Map<String, Object> value) {
        given().contentType("application/json").body(Map.of("value", value))
            .when().put(API + "/tables/" + table + "/rows")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("version", notNullValue());
    }

    @Test
    void tableLifecycleMatchesTerraformReadBack() {
        String name = "tf_" + System.nanoTime();
        String ddl = TERRAFORM_DDL.replace("test_table", name);
        Map<String, Object> limits = Map.of("maxReadUnits", 11, "maxWriteUnits", 11, "maxStorageInGBs", 11);
        String tableId = createTable(name, ddl, limits);
        assertThat(tableId, startsWith("ocid1.nosqltable.oc1."));

        given()
            .when().get(API + "/tables/" + tableId)
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("id", equalTo(tableId))
                .body("compartmentId", equalTo(compartment))
                .body("name", equalTo(name))
                .body("ddlStatement", equalTo(ddl))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("schemaState", equalTo("MUTABLE"))
                .body("isAutoReclaimable", equalTo(false))
                .body("isMultiRegion", equalTo(false))
                .body("replicas", hasSize(0))
                .body("tableLimits.capacityMode", equalTo("PROVISIONED"))
                .body("tableLimits.maxReadUnits", equalTo(11))
                .body("schema.columns", hasSize(5))
                .body("schema.columns[0].name", equalTo("id"))
                .body("schema.columns[0].type", equalTo("INTEGER"))
                .body("schema.columns[0].isNullable", equalTo(false))
                .body("schema.columns[0].isAsUuid", equalTo(false))
                .body("schema.columns[0].isGenerated", equalTo(false))
                .body("schema.columns[4].name", equalTo("guid"))
                .body("schema.columns[4].isAsUuid", equalTo(true))
                .body("schema.columns[4].isGenerated", equalTo(false))
                .body("schema.primaryKey", equalTo(List.of("id")))
                .body("schema.shardKey", equalTo(List.of("id")))
                .body("schema.ttl", equalTo(0))
                .body("schema.identity.columnName", equalTo("id"))
                .body("schema.identity.isAlways", equalTo(true))
                .body("schema.identity.isNull", equalTo(false));

        given().queryParam("compartmentId", compartment)
            .when().get(API + "/tables/" + name)
            .then().statusCode(200).body("id", equalTo(tableId));

        given().queryParam("compartmentId", compartment).queryParam("name", name)
            .when().get(API + "/tables")
            .then().statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].id", equalTo(tableId))
                .body("items[0].schema", nullValue())
                .body("availableReplicationRegions", hasSize(0));

        String alter = "ALTER TABLE " + name + " (ADD email STRING)";
        String updateWr = given().contentType("application/json").body(Map.of("ddlStatement", alter))
            .when().put(API + "/tables/" + tableId)
            .then().statusCode(202).body(emptyOrNullString()).extract().header("opc-work-request-id");
        given()
            .when().get(API + "/workRequests/" + updateWr)
            .then().statusCode(200)
                .body("operationType", equalTo("UPDATE_TABLE"))
                .body("resources[0].entityType", equalTo("TABLE"))
                .body("resources[0].actionType", equalTo("UPDATED"))
                .body("resources[0].identifier", equalTo(tableId));
        given()
            .when().get(API + "/tables/" + tableId)
            .then().statusCode(200)
                .body("ddlStatement", equalTo(alter))
                .body("schema.columns", hasSize(6));

        given().queryParam("compartmentId", compartment)
            .when().get(API + "/workRequests")
            .then().statusCode(200).body("items.id", hasItem(updateWr));
        given().when().get(API + "/workRequests/" + updateWr + "/errors")
            .then().statusCode(200).body("items", hasSize(0));
        given().when().get(API + "/workRequests/" + updateWr + "/logs")
            .then().statusCode(200).body("items", hasSize(0));
        given().when().delete(API + "/workRequests/" + updateWr)
            .then().statusCode(409).body("code", equalTo("Conflict"));
        given().when().get(API + "/tables/" + tableId + "/usage")
            .then().statusCode(200).body("items", hasSize(0));

        String deleteWr = given()
            .when().delete(API + "/tables/" + tableId)
            .then().statusCode(202).extract().header("opc-work-request-id");
        given()
            .when().get(API + "/workRequests/" + deleteWr)
            .then().statusCode(200)
                .body("operationType", equalTo("DELETE_TABLE"))
                .body("resources[0].actionType", equalTo("DELETED"));
        given()
            .when().get(API + "/tables/" + tableId)
            .then().statusCode(200).body("lifecycleState", equalTo("DELETED"));
        given().queryParam("compartmentId", compartment)
            .when().get(API + "/tables/" + name)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void missingTableHasTheExactNotFoundBody() {
        given().queryParam("compartmentId", compartment)
            .when().get(API + "/tables/nope")
            .then().statusCode(404)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("NotAuthorizedOrNotFound"))
                .body("message", equalTo("Authorization failed or requested resource not found: table nope"));

        given()
            .when().get(API + "/tables/nope")
            .then().statusCode(400)
                .body("code", equalTo("MissingParameter"))
                .body("message", equalTo("compartmentId is required when tableNameOrId is a table name: nope"));

        given().contentType("application/json")
                .body(Map.of("name", "bad", "compartmentId", compartment, "ddlStatement", "CREATE TABLE bad (id"))
            .when().post(API + "/tables")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
    }

    @Test
    void indexesThroughTheWire() {
        String name = "idx_" + System.nanoTime();
        String tableId = createTable(name, "CREATE TABLE " + name + " (id INTEGER, info JSON, PRIMARY KEY (id))",
                null);
        String wr = given().contentType("application/json")
                .body(Map.of("name", "cityIdx", "keys",
                        List.of(Map.of("columnName", "info", "jsonPath", "city", "jsonFieldType", "STRING"))))
            .when().post(API + "/tables/" + tableId + "/indexes")
            .then().statusCode(202).body(emptyOrNullString()).extract().header("opc-work-request-id");
        given()
            .when().get(API + "/workRequests/" + wr)
            .then().statusCode(200)
                .body("operationType", equalTo("UPDATE_TABLE"))
                .body("resources[0].entityType", equalTo("TABLE"))
                .body("resources[0].actionType", equalTo("UPDATED"))
                .body("resources[0].identifier", equalTo(tableId));

        given().queryParam("compartmentId", compartment)
            .when().get(API + "/tables/" + name + "/indexes/cityIdx")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("name", equalTo("cityIdx"))
                .body("tableId", equalTo(tableId))
                .body("tableName", equalTo(name))
                .body("compartmentId", equalTo(compartment))
                .body("keys[0].columnName", equalTo("info"))
                .body("keys[0].jsonPath", equalTo("city"))
                .body("lifecycleState", equalTo("ACTIVE"));
        given()
            .when().get(API + "/tables/" + tableId + "/indexes")
            .then().statusCode(200).body("items.name", equalTo(List.of("cityIdx")));

        given().when().delete(API + "/tables/" + tableId + "/indexes/cityIdx")
            .then().statusCode(202).header("opc-work-request-id", notNullValue());
        given().when().get(API + "/tables/" + tableId + "/indexes/cityIdx")
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void rowsWithRepeatedKeyParams() {
        String name = "rows_" + System.nanoTime();
        String tableId = createTable(name, "CREATE TABLE " + name + " (tenant STRING, id INTEGER, data JSON,"
                + " PRIMARY KEY (SHARD(tenant), id))", null);
        putRow(tableId, Map.of("tenant", "acme", "id", 1, "data", Map.of("color", "red")));

        String etag = given().queryParam("key", "tenant:acme", "id:1")
            .when().get(API + "/tables/" + tableId + "/rows")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("value.tenant", equalTo("acme"))
                .body("value.data.color", equalTo("red"))
                .body("usage.readUnitsConsumed", notNullValue())
                .extract().header("etag");

        given().queryParam("key", "tenant:acme", "id:2")
            .when().get(API + "/tables/" + tableId + "/rows")
            .then().statusCode(200).body("value", nullValue());

        given().contentType("application/json")
                .body(Map.of("value", Map.of("tenant", "acme", "id", 1), "option", "IF_ABSENT",
                        "isGetReturnRow", true))
            .when().put(API + "/tables/" + tableId + "/rows")
            .then().statusCode(200)
                .header("etag", nullValue())
                .body("version", nullValue())
                .body("existingVersion", equalTo(etag))
                .body("existingValue.data.color", equalTo("red"));

        given().queryParam("key", "tenant:acme", "id:1").header("if-match", "stale")
            .when().delete(API + "/tables/" + tableId + "/rows")
            .then().statusCode(200).body("isSuccess", equalTo(false));
        given().queryParam("key", "tenant:acme", "id:1")
            .when().delete(API + "/tables/" + tableId + "/rows")
            .then().statusCode(200).body("isSuccess", equalTo(true));

        given().queryParam("key", "id:1")
            .when().get(API + "/tables/" + tableId + "/rows")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
    }

    @Test
    void queryPagesThroughResults() {
        String name = "q_" + System.nanoTime();
        String tableId = createTable(name, "CREATE TABLE " + name + " (id INTEGER, name STRING, PRIMARY KEY (id))",
                null);
        for (int i = 1; i <= 5; i++) {
            putRow(tableId, Map.of("id", i, "name", "n" + i));
        }
        String statement = "SELECT id, name FROM " + name + " WHERE id >= 2 ORDER BY id";

        Response first = given().contentType("application/json").queryParam("limit", 2)
                .body(Map.of("compartmentId", compartment, "statement", statement))
            .when().post(API + "/query")
            .then().statusCode(200)
                .header("opc-next-page", notNullValue())
                .body("items.id", equalTo(List.of(2, 3)))
                .body("usage.readUnitsConsumed", equalTo(5))
                .extract().response();
        given().contentType("application/json").queryParam("limit", 2)
                .queryParam("page", first.header("opc-next-page"))
                .body(Map.of("compartmentId", compartment, "statement", statement))
            .when().post(API + "/query")
            .then().statusCode(200)
                .header("opc-next-page", nullValue())
                .body("items.id", equalTo(List.of(4, 5)));

        String prepared = given().queryParam("compartmentId", compartment)
                .queryParam("statement", "DECLARE $id INTEGER; SELECT name FROM " + name + " WHERE id = $id")
            .when().get(API + "/query/prepare")
            .then().statusCode(200).body("statement", notNullValue()).extract().path("statement");
        given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "statement", prepared, "isPrepared", true,
                        "variables", Map.of("$id", 3)))
            .when().post(API + "/query")
            .then().statusCode(200).body("items[0].name", equalTo("n3"));

        given().queryParam("compartmentId", compartment).queryParam("statement", "SELECT * FROM " + name)
            .when().get(API + "/query/summarize")
            .then().statusCode(200)
                .body("operation", equalTo("SELECT"))
                .body("tableName", equalTo(name));
        given().queryParam("compartmentId", compartment).queryParam("statement", "SELEC * FROM " + name)
            .when().get(API + "/query/summarize")
            .then().statusCode(200).body("syntaxError", notNullValue());
    }
}
