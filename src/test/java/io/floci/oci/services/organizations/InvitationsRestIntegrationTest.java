package io.floci.oci.services.organizations;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class InvitationsRestIntegrationTest {

    private static String signedAs(String tenancyId) {
        return "Signature version=\"1\",keyId=\"" + tenancyId + "/ocid1.user.oc1..invuser/aa:bb\","
                + "algorithm=\"rsa-sha256\",headers=\"date (request-target) host\","
                + "signature=\"ZmFrZXNpZ25hdHVyZQ==\"";
    }

    private static String tenancy(String name) {
        return "ocid1.tenancy.oc1..inv" + name + System.nanoTime();
    }

    private static Map<String, String> invite(String sender, String recipient) {
        return given().header("Authorization", signedAs(sender))
                .contentType("application/json")
                .body(Map.of("compartmentId", sender, "recipientTenancyId", recipient,
                        "displayName", "join us", "features", List.of("SUBSCRIPTION_SHARING")))
            .when().post("/20230401/senderInvitations")
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("opc-work-request-id", notNullValue())
                .header("etag", notNullValue())
                .body("compartmentId", equalTo(sender))
                .body("recipientTenancyId", equalTo(recipient))
                .body("status", equalTo("PENDING"))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("subjects", equalTo(List.of("LINK")))
                .body("invitationFeatures[0].name", equalTo("SUBSCRIPTION_SHARING"))
                .extract().jsonPath().getMap("", String.class, String.class);
    }

    @Test
    void inviteAndAcceptJoinsTheOrganization() {
        String sender = tenancy("sender");
        String recipient = tenancy("recipient");
        Map<String, String> sent = invite(sender, recipient);
        String recipientInvitationId = sent.get("recipientInvitationId");

        given().header("Authorization", signedAs(recipient)).queryParam("compartmentId", recipient)
            .when().get("/20230401/recipientInvitations")
            .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].id", equalTo(recipientInvitationId))
                .body("items[0].senderInvitationId", equalTo(sent.get("id")))
                .body("items[0].senderTenancyId", equalTo(sender));

        String workRequestId = given().header("Authorization", signedAs(recipient))
            .when().post("/20230401/recipientInvitations/" + recipientInvitationId + "/actions/accept")
            .then()
                .statusCode(202)
                .header("opc-request-id", notNullValue())
                .header("opc-work-request-id", notNullValue())
                .extract().header("opc-work-request-id");
        given().header("Authorization", signedAs(recipient))
            .when().get("/20230401/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("operationType", equalTo("ACCEPT_RECIPIENT_INVITATION"))
                .body("status", equalTo("SUCCEEDED"));

        given().header("Authorization", signedAs(sender)).queryParam("childTenancyId", recipient)
            .when().get("/20230401/links")
            .then().statusCode(200)
                .body("items.feature", hasItems("CORE", "SUBSCRIPTION_SHARING"));
        given().header("Authorization", signedAs(sender))
            .when().get("/20230401/senderInvitations/" + sent.get("id"))
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("status", equalTo("ACCEPTED"));
        given().header("Authorization", signedAs(recipient)).queryParam("compartmentId", recipient)
            .when().get("/20230401/organizations")
            .then().statusCode(200)
                .body("items[0].compartmentId", equalTo(sender));
    }

    @Test
    void ignoreReturnsTheInvitationAndCancelEndsIt() {
        String sender = tenancy("sender");
        String recipient = tenancy("recipient");
        Map<String, String> sent = invite(sender, recipient);

        given().header("Authorization", signedAs(recipient))
            .when().post("/20230401/recipientInvitations/" + sent.get("recipientInvitationId") + "/actions/ignore")
            .then()
                .statusCode(200)
                .header("etag", notNullValue())
                .body("status", equalTo("IGNORED"));

        given().header("Authorization", signedAs(sender))
            .when().post("/20230401/senderInvitations/" + sent.get("id") + "/actions/cancel")
            .then()
                .statusCode(202)
                .header("opc-work-request-id", notNullValue());
        given().header("Authorization", signedAs(sender))
            .when().post("/20230401/senderInvitations/" + sent.get("id") + "/actions/cancel")
            .then()
                .statusCode(409)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("Conflict"));
    }

    @Test
    void updateHonoursIfMatch() {
        String sender = tenancy("sender");
        Map<String, String> sent = invite(sender, tenancy("recipient"));

        given().header("Authorization", signedAs(sender)).header("if-match", "stale")
                .contentType("application/json").body(Map.of("displayName", "renamed"))
            .when().put("/20230401/senderInvitations/" + sent.get("id"))
            .then().statusCode(412);
        given().header("Authorization", signedAs(sender))
                .contentType("application/json").body(Map.of("displayName", "renamed"))
            .when().put("/20230401/senderInvitations/" + sent.get("id"))
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("displayName", equalTo("renamed"));
    }

    @Test
    void listsRequireTheCallersCompartmentAndValidEnums() {
        String sender = tenancy("sender");

        given().header("Authorization", signedAs(sender))
            .when().get("/20230401/senderInvitations")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
        given().header("Authorization", signedAs(sender)).queryParam("compartmentId", tenancy("other"))
            .when().get("/20230401/recipientInvitations")
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
        given().header("Authorization", signedAs(sender)).queryParam("compartmentId", sender)
                .queryParam("status", "IGNORED")
            .when().get("/20230401/senderInvitations")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));
    }
}
