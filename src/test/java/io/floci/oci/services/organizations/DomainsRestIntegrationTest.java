package io.floci.oci.services.organizations;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class DomainsRestIntegrationTest {

    private static String signedAs(String tenancyId) {
        return "Signature version=\"1\",keyId=\"" + tenancyId + "/ocid1.user.oc1..domuser/aa:bb\","
                + "algorithm=\"rsa-sha256\",headers=\"date (request-target) host\","
                + "signature=\"ZmFrZXNpZ25hdHVyZQ==\"";
    }

    @Test
    void claimGovernAndReleaseADomain() {
        String owner = "ocid1.tenancy.oc1..domrest" + System.nanoTime();
        String domainName = "d" + System.nanoTime() + ".example.com";

        String domainId = given().header("Authorization", signedAs(owner)).contentType("application/json")
                .body(Map.of("compartmentId", owner, "domainName", domainName))
            .when().post("/20230401/domains")
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("opc-work-request-id", notNullValue())
                .header("etag", notNullValue())
                .body("domainName", equalTo(domainName))
                .body("ownerId", equalTo(owner))
                .body("status", equalTo("ACTIVE"))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("txtRecord", startsWith("oci-domain-verification="))
                .extract().path("id");

        given().header("Authorization", signedAs(owner)).queryParam("compartmentId", owner)
            .when().get("/20230401/domains")
            .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].id", equalTo(domainId));

        String governanceId = given().header("Authorization", signedAs(owner)).contentType("application/json")
                .body(Map.of("compartmentId", owner, "domainId", domainId, "subscriptionEmail", "ops@example.com",
                        "onsTopicId", "ocid1.onstopic.oc1.iad.t", "onsSubscriptionId", "ocid1.onssubscription.oc1.iad.s"))
            .when().post("/20230401/domainGovernances")
            .then()
                .statusCode(200)
                .header("etag", notNullValue())
                .body("domainId", equalTo(domainId))
                .body("isGovernanceEnabled", equalTo(true))
                .body("lifecycleState", equalTo("ACTIVE"))
                .extract().path("id");

        given().header("Authorization", signedAs(owner))
            .when().delete("/20230401/domains/" + domainId)
            .then().statusCode(204).header("opc-request-id", notNullValue());
        given().header("Authorization", signedAs(owner))
            .when().get("/20230401/domains/" + domainId)
            .then().statusCode(200)
                .body("lifecycleState", equalTo("DELETED"))
                .body("status", equalTo("RELEASED"));
        given().header("Authorization", signedAs(owner))
            .when().get("/20230401/domainGovernances/" + governanceId)
            .then().statusCode(200).body("lifecycleState", equalTo("INACTIVE"));
    }

    @Test
    void aSecondClaimOnTheSameDomainIsAConflict() {
        String domainName = "c" + System.nanoTime() + ".example.com";
        String first = "ocid1.tenancy.oc1..domfirst" + System.nanoTime();
        String second = "ocid1.tenancy.oc1..domsecond" + System.nanoTime();
        given().header("Authorization", signedAs(first)).contentType("application/json")
                .body(Map.of("compartmentId", first, "domainName", domainName))
            .when().post("/20230401/domains")
            .then().statusCode(200);

        given().header("Authorization", signedAs(second)).contentType("application/json")
                .body(Map.of("compartmentId", second, "domainName", domainName))
            .when().post("/20230401/domains")
            .then()
                .statusCode(409)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("Conflict"));
    }

    @Test
    void listsNeedTheCallersCompartment() {
        String owner = "ocid1.tenancy.oc1..domlist" + System.nanoTime();

        given().header("Authorization", signedAs(owner)).contentType("application/json")
                .body(Map.of("compartmentId", owner, "domainName", "not a domain"))
            .when().post("/20230401/domains")
            .then()
                .statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("InvalidParameter"));
        given().header("Authorization", signedAs(owner))
            .when().get("/20230401/domainGovernances")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
        given().header("Authorization", signedAs(owner)).queryParam("compartmentId", "ocid1.tenancy.oc1..someoneelse")
            .when().get("/20230401/domains")
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }
}
