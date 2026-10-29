package io.floci.oci.services.governancerules;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class GovernanceRulesRestIntegrationTest {

    private static String signedAs(String tenancyId) {
        return "Signature version=\"1\",keyId=\"" + tenancyId + "/ocid1.user.oc1..govuser/aa:bb\","
                + "algorithm=\"rsa-sha256\",headers=\"date (request-target) host\","
                + "signature=\"ZmFrZXNpZ25hdHVyZQ==\"";
    }

    private static String freshParent() {
        return "ocid1.tenancy.oc1..govparent" + System.nanoTime();
    }

    private static String createChild(String parent) {
        String workRequestId = given().header("Authorization", signedAs(parent))
                .contentType("application/json")
                .body(Map.of("compartmentId", parent, "tenancyName", "gov-child-" + System.nanoTime(),
                        "homeRegion", "us-ashburn-1", "adminEmail", "admin@example.com"))
            .when().post("/20230401/childTenancies")
            .then().statusCode(202).extract().header("opc-work-request-id");
        return given().header("Authorization", signedAs(parent))
            .when().get("/20230401/workRequests/" + workRequestId)
            .then().statusCode(200).extract().path("resources[0].identifier");
    }

    private static String createAllowedRegionsRule(String parent, List<String> regions) {
        return given().header("Authorization", signedAs(parent))
                .contentType("application/json")
                .body(Map.of("compartmentId", parent, "displayName", "only-ashburn", "type", "ALLOWED_REGIONS",
                        "creationOption", "TEMPLATE",
                        "template", Map.of("type", "ALLOWED_REGIONS", "displayName", "only-ashburn",
                                "regions", regions)))
            .when().post("/20220504/governanceRules")
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("opc-work-request-id", notNullValue())
                .header("etag", notNullValue())
                .body("type", equalTo("ALLOWED_REGIONS"))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("template.regions", equalTo(regions))
                .extract().path("id");
    }

    private static void includeAll(String parent, String ruleId) {
        given().header("Authorization", signedAs(parent))
                .contentType("application/json")
                .body(Map.of("governanceRuleId", ruleId, "type", "ALL"))
            .when().post("/20220504/inclusionCriteria")
            .then()
                .statusCode(200)
                .header("etag", notNullValue())
                .header("opc-work-request-id", notNullValue())
                .body("type", equalTo("ALL"));
    }

    @Test
    void allowedRegionsBlocksAGovernedChildsSubscriptionUntilItOptsOut() {
        String parent = freshParent();
        String child = createChild(parent);
        String ruleId = createAllowedRegionsRule(parent, List.of("us-ashburn-1"));
        includeAll(parent, ruleId);

        given().header("Authorization", signedAs(parent)).queryParam("compartmentId", parent)
                .queryParam("childTenancyId", child)
            .when().get("/20220504/tenancyAttachments")
            .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].lifecycleState", equalTo("ACTIVE"))
                .body("items[0].governanceRuleId", equalTo(ruleId));
        given().header("Authorization", signedAs(child)).queryParam("compartmentId", child)
            .when().get("/20220504/enforcedGovernanceRules")
            .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].governanceRuleDisplayName", equalTo("only-ashburn"))
                .body("items[0].type", equalTo("ALLOWED_REGIONS"));

        given().header("Authorization", signedAs(child)).contentType("application/json")
                .body(Map.of("regionKey", "PHX"))
            .when().post("/20160918/tenancies/" + child + "/regionSubscriptions")
            .then()
                .statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("InvalidParameter"))
                .body("message", containsString("us-phoenix-1"));

        String organizationId = given().header("Authorization", signedAs(parent))
                .queryParam("compartmentId", parent)
            .when().get("/20230401/organizations")
            .then().statusCode(200).extract().path("items[0].id");
        String optOut = given().header("Authorization", signedAs(parent))
            .when().post("/20230401/organizations/" + organizationId + "/tenancies/" + child
                    + "/actions/removeGovernance")
            .then()
                .statusCode(202)
                .header("opc-work-request-id", notNullValue())
                .extract().header("opc-work-request-id");
        given().header("Authorization", signedAs(parent))
            .when().get("/20230401/workRequests/" + optOut)
            .then().statusCode(200).body("operationType", equalTo("SELF_OPT_OUT"));
        given().header("Authorization", signedAs(parent))
            .when().get("/20230401/organizations/" + organizationId + "/tenancies/" + child)
            .then().statusCode(200).body("governanceStatus", equalTo("OPTED_OUT"));

        given().header("Authorization", signedAs(child)).contentType("application/json")
                .body(Map.of("regionKey", "PHX"))
            .when().post("/20160918/tenancies/" + child + "/regionSubscriptions")
            .then().statusCode(200);

        given().header("Authorization", signedAs(parent))
            .when().post("/20230401/organizations/" + organizationId + "/tenancies/" + child
                    + "/actions/removeGovernance")
            .then().statusCode(409).body("code", equalTo("Conflict"));
        given().header("Authorization", signedAs(parent))
            .when().post("/20230401/organizations/" + organizationId + "/tenancies/" + parent
                    + "/actions/addGovernance")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
    }

    @Test
    void ruleLifecycleThroughWorkRequests() {
        String parent = freshParent();
        String ruleId = createAllowedRegionsRule(parent, List.of("us-ashburn-1"));

        String etag = given().header("Authorization", signedAs(parent))
            .when().get("/20220504/governanceRules/" + ruleId)
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("creationOption", equalTo("TEMPLATE"))
                .extract().header("etag");

        String updateId = given().header("Authorization", signedAs(parent)).header("if-match", etag)
                .contentType("application/json").body(Map.of("displayName", "renamed"))
            .when().put("/20220504/governanceRules/" + ruleId)
            .then().statusCode(202).extract().header("opc-work-request-id");
        given().header("Authorization", signedAs(parent))
            .when().get("/20220504/workRequests/" + updateId)
            .then().statusCode(200)
                .body("operationType", equalTo("UPDATE_GOVERNANCE_RULE"))
                .body("status", equalTo("SUCCEEDED"))
                .body("resources[0].identifier", equalTo(ruleId));

        given().header("Authorization", signedAs(parent)).queryParam("compartmentId", parent)
            .when().get("/20220504/governanceRules")
            .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].displayName", equalTo("renamed"))
                .body("items[0].template", equalTo(null));

        given().header("Authorization", signedAs(parent))
            .when().delete("/20220504/governanceRules/" + ruleId)
            .then().statusCode(202).header("opc-work-request-id", notNullValue());
        given().header("Authorization", signedAs(parent))
            .when().get("/20220504/governanceRules/" + ruleId)
            .then().statusCode(200).body("lifecycleState", equalTo("DELETED"));

        given().header("Authorization", signedAs(parent)).queryParam("compartmentId", parent)
                .queryParam("resourceId", ruleId)
            .when().get("/20220504/workRequests")
            .then().statusCode(200).body("items.size()", equalTo(3));
        given().header("Authorization", signedAs(parent))
            .when().delete("/20220504/workRequests/" + updateId)
            .then().statusCode(409).body("code", equalTo("Conflict"));
    }

    @Test
    void errorsUseTheOciShape() {
        String parent = freshParent();

        given().header("Authorization", signedAs(parent))
            .when().get("/20220504/governanceRules")
            .then()
                .statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("MissingParameter"));
        given().header("Authorization", signedAs(parent))
            .when().get("/20220504/governanceRules/ocid1.governancerule.oc1..missing")
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
        given().header("Authorization", signedAs(parent)).contentType("application/json")
                .body(Map.of("compartmentId", parent, "displayName", "x", "type", "BUDGET",
                        "creationOption", "TEMPLATE", "template", Map.of()))
            .when().post("/20220504/governanceRules")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
    }
}
