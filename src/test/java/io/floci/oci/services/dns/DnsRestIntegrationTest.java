package io.floci.oci.services.dns;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class DnsRestIntegrationTest {

    private static String compartment;

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("itdns");
        }
    }

    private static String uniqueZone(String prefix) {
        return prefix + System.nanoTime() + ".example";
    }

    private static String createGlobalZone(String name) {
        return given().contentType("application/json")
                .body(Map.of("name", name, "compartmentId", compartment, "zoneType", "PRIMARY"))
            .when().post("/20180115/zones")
            .then().statusCode(201)
                .extract().path("id");
    }

    @Test
    void zoneLifecycleByNameAndId() {
        String name = uniqueZone("life");
        String id = given().contentType("application/json")
                .body(Map.of("name", name + ".", "compartmentId", compartment, "freeformTags", Map.of("k", "v")))
            .when().post("/20180115/zones")
            .then().statusCode(201)
                .header("opc-request-id", notNullValue())
                .header("etag", notNullValue())
                .header("Location", containsString("/20180115/zones/ocid1.dns-zone."))
                .body("name", equalTo(name))
                .body("zoneType", equalTo("PRIMARY"))
                .body("scope", equalTo("GLOBAL"))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("isProtected", equalTo(false))
                .body("serial", equalTo(1))
                .body("version", equalTo("1"))
                .body("resolutionMode", equalTo("STATIC"))
                .body("dnssecState", equalTo("DISABLED"))
                .body("externalMasters", empty())
                .body("nameservers.hostname", hasItem("ns1.dns.localhost.floci.io"))
                .body("self", containsString("/20180115/zones/ocid1.dns-zone."))
                .extract().path("id");

        given().when().get("/20180115/zones/" + name)
            .then().statusCode(200).header("etag", notNullValue()).body("id", equalTo(id));
        given().when().get("/20180115/zones/" + id)
            .then().statusCode(200).body("name", equalTo(name)).body("freeformTags.k", equalTo("v"));
        given().queryParam("compartmentId", compartment).queryParam("name", name)
            .when().get("/20180115/zones")
            .then().statusCode(200)
                .header("opc-total-items", "1")
                .body("size()", equalTo(1))
                .body("[0].id", equalTo(id))
                .body("[0].nameservers", nullValue());

        given().contentType("application/json").body(Map.of("freeformTags", Map.of("k", "w")))
            .when().put("/20180115/zones/" + id)
            .then().statusCode(200).header("etag", notNullValue()).body("freeformTags.k", equalTo("w"));

        given().contentType("application/json").body(Map.of("name", name, "compartmentId", compartment))
            .when().post("/20180115/zones")
            .then().statusCode(409).body("code", equalTo("Conflict"));

        given().when().delete("/20180115/zones/" + id).then().statusCode(204);
        given().when().get("/20180115/zones/" + id)
            .then().statusCode(404)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void recordsAreACollectionWithTotalAndEtag() {
        String name = uniqueZone("rec");
        String id = createGlobalZone(name);

        given().when().get("/20180115/zones/" + id + "/records")
            .then().statusCode(200)
                .header("opc-total-items", "5")
                .header("etag", notNullValue())
                .body("items.size()", equalTo(5))
                .body("items.findAll { it.rtype == 'SOA' }[0].isProtected", equalTo(true))
                .body("items.findAll { it.rtype == 'NS' }.size()", equalTo(4))
                .body("items[0].recordHash", notNullValue())
                .body("items[0].rrsetVersion", equalTo("1"));

        given().contentType("application/json").body(Map.of("items", List.of(
                    Map.of("operation", "PROHIBIT", "domain", "www." + name, "rtype", "A"),
                    Map.of("operation", "ADD", "domain", "www." + name, "rtype", "A", "rdata", "192.0.2.10",
                            "ttl", 30))))
            .when().patch("/20180115/zones/" + id + "/records/www." + name + "/A")
            .then().statusCode(200)
                .header("opc-total-items", "1")
                .body("items[0].rdata", equalTo("192.0.2.10"))
                .body("items[0].rrsetVersion", equalTo("2"))
                .body("items[0].isProtected", equalTo(false));

        given().contentType("application/json").body(Map.of("items", List.of(
                    Map.of("operation", "PROHIBIT", "domain", "www." + name, "rtype", "A"))))
            .when().patch("/20180115/zones/" + id + "/records")
            .then().statusCode(412)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("NoEtagMatch"));

        given().contentType("application/json").body(Map.of("items", List.of(
                    Map.of("domain", "alias." + name, "rtype", "CNAME", "rdata", "www." + name, "ttl", 60))))
            .when().put("/20180115/zones/" + id + "/records/alias." + name + "/CNAME")
            .then().statusCode(200).body("items[0].rdata", equalTo("www." + name + "."));
        given().when().get("/20180115/zones/" + name + "/records/alias." + name + "/CNAME")
            .then().statusCode(200).body("items.size()", equalTo(1));
        given().queryParam("rtype", "A").when().get("/20180115/zones/" + id + "/records")
            .then().statusCode(200).header("opc-total-items", "1");
        given().when().get("/20180115/zones/" + id + "/records/www." + name)
            .then().statusCode(200).body("items.rtype", contains("A"));

        given().when().delete("/20180115/zones/" + id + "/records/alias." + name + "/CNAME").then().statusCode(204);
        given().when().get("/20180115/zones/" + id + "/records/alias." + name + "/CNAME")
            .then().statusCode(200).body("items", empty());
        given().when().delete("/20180115/zones/" + id + "/records/" + name + "/NS")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
    }

    @Test
    void conditionalRequests() {
        String id = createGlobalZone(uniqueZone("cond"));
        String etag = given().when().get("/20180115/zones/" + id).then().statusCode(200).extract().header("etag");

        given().header("If-None-Match", etag).when().get("/20180115/zones/" + id).then().statusCode(304);
        given().header("If-None-Match", etag).when().get("/20180115/zones/" + id + "/records").then().statusCode(304);
        given().header("If-Match", "stale").contentType("application/json")
                .body(Map.of("freeformTags", Map.of("a", "b")))
            .when().put("/20180115/zones/" + id)
            .then().statusCode(412).body("code", equalTo("NoEtagMatch"));
        given().header("If-Match", etag).contentType("application/json")
                .body(Map.of("freeformTags", Map.of("a", "b")))
            .when().put("/20180115/zones/" + id)
            .then().statusCode(200).header("etag", not(equalTo(etag)));
    }

    @Test
    void viewsAndPrivateZones() {
        String viewId = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", "it-view"))
            .when().post("/20180115/views")
            .then().statusCode(201)
                .header("etag", notNullValue())
                .body("displayName", equalTo("it-view"))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("isProtected", equalTo(false))
                .body("id", startsWith("ocid1.dnsview.oc1.iad."))
                .extract().path("id");
        given().queryParam("compartmentId", compartment).queryParam("displayName", "it-view")
            .when().get("/20180115/views")
            .then().statusCode(200).body("size()", greaterThanOrEqualTo(1)).body("id", hasItem(viewId));

        String name = uniqueZone("priv");
        given().contentType("application/json")
                .body(Map.of("name", name, "compartmentId", compartment, "scope", "PRIVATE"))
            .when().post("/20180115/zones")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
        String zoneId = given().contentType("application/json")
                .body(Map.of("name", name, "compartmentId", compartment, "scope", "PRIVATE", "viewId", viewId))
            .when().post("/20180115/zones")
            .then().statusCode(201).body("scope", equalTo("PRIVATE")).body("viewId", equalTo(viewId))
                .extract().path("id");
        given().queryParam("scope", "PRIVATE").when().get("/20180115/zones/" + name)
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
        given().queryParam("scope", "PRIVATE").queryParam("viewId", viewId).when().get("/20180115/zones/" + name)
            .then().statusCode(200).body("id", equalTo(zoneId));

        given().when().delete("/20180115/views/" + viewId)
            .then().statusCode(409).body("code", equalTo("Conflict"));
        given().when().delete("/20180115/zones/" + zoneId).then().statusCode(204);
        given().contentType("application/json").body(Map.of("displayName", "renamed"))
            .when().put("/20180115/views/" + viewId)
            .then().statusCode(200).body("displayName", equalTo("renamed"));
        given().when().delete("/20180115/views/" + viewId).then().statusCode(204);
    }

    @Test
    void listRequiresCompartment() {
        given().when().get("/20180115/zones")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
        given().contentType("application/json").body("{}")
            .when().post("/20180115/actions/createZoneFromZoneFile")
            .then().statusCode(501).body("code", equalTo("MethodNotImplemented"));
    }
}
