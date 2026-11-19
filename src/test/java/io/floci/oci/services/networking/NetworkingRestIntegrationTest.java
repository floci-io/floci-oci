package io.floci.oci.services.networking;

import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class NetworkingRestIntegrationTest {

    private static String compartment;
    private static String otherCompartment;

    @BeforeEach
    void createTestCompartments() {
        if (compartment == null) {
            compartment = TestCompartments.create("itnetworking");
            otherCompartment = TestCompartments.create("itnetworkingother");
        }
    }

    private String createVcn(String cidr, String dnsLabel) {
        return given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "cidrBlocks", List.of(cidr),
                        "displayName", "it-vcn", "dnsLabel", dnsLabel))
            .when().post("/20160918/vcns")
            .then().statusCode(200)
                .header("etag", notNullValue())
                .header("opc-request-id", notNullValue())
                .extract().path("id");
    }

    @Test
    void vcnCrudRoundtrip() {
        String vcnId = createVcn("10.10.0.0/16", "itvcn");

        given().when().get("/20160918/vcns/" + vcnId)
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("id", equalTo(vcnId))
                .body("compartmentId", equalTo(compartment))
                .body("lifecycleState", equalTo("AVAILABLE"))
                .body("cidrBlock", equalTo("10.10.0.0/16"))
                .body("cidrBlocks", contains("10.10.0.0/16"))
                .body("dnsLabel", equalTo("itvcn"))
                .body("vcnDomainName", equalTo("itvcn.oraclevcn.com"))
                .body("defaultRouteTableId", startsWith("ocid1.routetable."))
                .body("defaultSecurityListId", startsWith("ocid1.securitylist."))
                .body("defaultDhcpOptionsId", startsWith("ocid1.dhcpoptions."))
                .body("ipv6CidrBlocks", empty())
                .body("systemTags", anEmptyMap());

        // Bare array, unlike the {"items":[...]} collections of Queue.
        given().queryParam("compartmentId", compartment)
            .when().get("/20160918/vcns")
            .then().statusCode(200)
                .body("find { it.id == '" + vcnId + "' }.displayName", equalTo("it-vcn"));

        given().contentType("application/json").body(Map.of("displayName", "it-vcn-renamed"))
            .when().put("/20160918/vcns/" + vcnId)
            .then().statusCode(200).body("displayName", equalTo("it-vcn-renamed"));

        given().when().delete("/20160918/vcns/" + vcnId)
            .then().statusCode(204);

        given().when().get("/20160918/vcns/" + vcnId)
            .then().statusCode(404)
                .body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void subnetGatewaysRouteTableAndDependencies() {
        String vcnId = createVcn("10.20.0.0/16", "itdeps");

        String subnetId = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "vcnId", vcnId, "cidrBlock", "10.20.1.0/24",
                        "dnsLabel", "app"))
            .when().post("/20160918/subnets")
            .then().statusCode(200)
                .body("vcnId", equalTo(vcnId))
                .body("virtualRouterIp", equalTo("10.20.1.1"))
                .body("virtualRouterMac", matchesPattern("00:00:17:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}"))
                .body("subnetDomainName", equalTo("app.itdeps.oraclevcn.com"))
                .body("prohibitPublicIpOnVnic", equalTo(false))
                .body("securityListIds", hasSize(1))
                .extract().path("id");

        String igwId = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "vcnId", vcnId, "isEnabled", true))
            .when().post("/20160918/internetGateways")
            .then().statusCode(200)
                .body("isEnabled", equalTo(true))
                .extract().path("id");

        String rtId = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "vcnId", vcnId,
                        "routeRules", List.of(Map.of("destination", "0.0.0.0/0", "networkEntityId", igwId))))
            .when().post("/20160918/routeTables")
            .then().statusCode(200)
                .body("routeRules[0].cidrBlock", equalTo("0.0.0.0/0"))
                .body("routeRules[0].destination", equalTo("0.0.0.0/0"))
                .body("routeRules[0].destinationType", equalTo("CIDR_BLOCK"))
                .body("routeRules[0].routeType", equalTo("STATIC"))
                .extract().path("id");

        given().contentType("application/json").body(Map.of("routeTableId", rtId))
            .when().put("/20160918/subnets/" + subnetId)
            .then().statusCode(200).body("routeTableId", equalTo(rtId));

        given().when().delete("/20160918/vcns/" + vcnId)
            .then().statusCode(409).body("code", equalTo("Conflict"));
        given().when().delete("/20160918/internetGateways/" + igwId)
            .then().statusCode(409).body("code", equalTo("Conflict"));
        given().when().delete("/20160918/routeTables/" + rtId)
            .then().statusCode(409).body("code", equalTo("Conflict"));

        given().when().delete("/20160918/subnets/" + subnetId).then().statusCode(204);
        given().when().delete("/20160918/routeTables/" + rtId).then().statusCode(204);
        given().when().delete("/20160918/internetGateways/" + igwId).then().statusCode(204);
        given().when().delete("/20160918/vcns/" + vcnId).then().statusCode(204);
    }

    @Test
    void subnetOutsideVcnIsInvalidParameter() {
        String vcnId = createVcn("10.30.0.0/16", "itbad");
        given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "vcnId", vcnId, "cidrBlock", "172.16.0.0/24"))
            .when().post("/20160918/subnets")
            .then().statusCode(400)
                .body("code", equalTo("InvalidParameter"));
    }

    @Test
    void unknownCompartmentIsRejected() {
        given().contentType("application/json")
                .body(Map.of("compartmentId", "ocid1.compartment.oc1..nope", "cidrBlock", "10.40.0.0/16"))
            .when().post("/20160918/vcns")
            .then().statusCode(400)
                .body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"));
    }

    @Test
    void servicesAndServiceGateway() {
        String vcnId = createVcn("10.50.0.0/16", "itsgw");
        String serviceId = given().when().get("/20160918/services")
            .then().statusCode(200)
                .body("size()", equalTo(2))
                .body("[0].name", equalTo("All IAD Services In Oracle Services Network"))
                .extract().path("[1].id");

        String sgwId = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "vcnId", vcnId,
                        "services", List.of(Map.of("serviceId", serviceId))))
            .when().post("/20160918/serviceGateways")
            .then().statusCode(200)
                .header("etag", nullValue())
                .body("services[0].serviceName", equalTo("OCI IAD Object Storage"))
                .body("blockTraffic", equalTo(false))
                .extract().path("id");

        given().contentType("application/json").body(Map.of("serviceId", serviceId))
            .when().post("/20160918/serviceGateways/" + sgwId + "/actions/detachService")
            .then().statusCode(200).body("services", empty());
    }

    @Test
    void networkSecurityGroupRules() {
        String vcnId = createVcn("10.60.0.0/16", "itnsg");
        String nsgId = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "vcnId", vcnId))
            .when().post("/20160918/networkSecurityGroups")
            .then().statusCode(200).extract().path("id");

        String ruleId = given().contentType("application/json")
                .body(Map.of("securityRules", List.of(Map.of("direction", "EGRESS", "protocol", "all",
                        "destination", "0.0.0.0/0"))))
            .when().post("/20160918/networkSecurityGroups/" + nsgId + "/actions/addSecurityRules")
            .then().statusCode(200)
                .body("securityRules[0].destinationType", equalTo("CIDR_BLOCK"))
                .body("securityRules[0].isStateless", equalTo(false))
                .extract().path("securityRules[0].id");

        given().queryParam("direction", "EGRESS")
            .when().get("/20160918/networkSecurityGroups/" + nsgId + "/securityRules")
            .then().statusCode(200).body("[0].id", equalTo(ruleId));

        given().queryParam("vcnId", vcnId)
            .when().get("/20160918/networkSecurityGroups")
            .then().statusCode(200).body("id", hasItem(nsgId));

        given().contentType("application/json").body(Map.of("securityRuleIds", List.of(ruleId)))
            .when().post("/20160918/networkSecurityGroups/" + nsgId + "/actions/removeSecurityRules")
            .then().statusCode(200);
    }

    @Test
    void vcnCompartmentMoveIsPolledThroughTheCoreWorkRequestPath() {
        String vcnId = createVcn("10.70.0.0/16", "itmove");
        String workRequestId = given().contentType("application/json")
                .body(Map.of("compartmentId", otherCompartment))
            .when().post("/20160918/vcns/" + vcnId + "/actions/changeCompartment")
            .then().statusCode(202)
                .header("etag", notNullValue())
                .extract().header("opc-work-request-id");

        given().when().get("/20160918/workRequests/" + workRequestId)
            .then().statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("resources[0].identifier", equalTo(vcnId));

        given().when().get("/20160918/vcns/" + vcnId)
            .then().statusCode(200).body("compartmentId", equalTo(otherCompartment));
    }

    @Test
    void listWithoutCompartmentIsMissingParameter() {
        given().when().get("/20160918/subnets")
            .then().statusCode(400).body("code", equalTo("MissingParameter"));
    }
}
