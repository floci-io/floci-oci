package io.floci.oci.services.networking;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.testing.TestCompartments;
import io.floci.oci.testing.TestNetworks;
import io.floci.oci.testing.TestRegions;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * OKE, Functions and PostgreSQL reject a VCN, subnet or network security group that does not
 * exist in the caller's tenancy and region, with the body-reference error of Oracle's catalog.
 */
@QuarkusTest
class NetworkReferencesRestIntegrationTest {

    private static final String MISSING_VCN = "ocid1.vcn.oc1.iad.doesnotexist";
    private static final String MISSING_SUBNET = "ocid1.subnet.oc1.iad.doesnotexist";
    private static final String MISSING_NSG = "ocid1.networksecuritygroup.oc1.iad.doesnotexist";

    private static String compartment;
    private static TestNetworks.Network network;

    @Inject
    EmulatorConfig config;

    @BeforeEach
    void createNetwork() {
        if (compartment == null) {
            compartment = TestCompartments.create("itnetrefs");
            network = TestNetworks.create(compartment);
        }
    }

    private static void rejects(String path, Object body, String type, String id) {
        given().contentType("application/json").body(body)
            .when().post(path)
            .then().statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"))
                .body("message", equalTo("Authorization failed or requested resource not found: " + type + " " + id));
    }

    @Test
    void okeRejectsAnUnknownVcn() {
        rejects("/20180222/clusters",
                Map.of("compartmentId", compartment, "name", "refs-vcn", "vcnId", MISSING_VCN),
                "vcn", MISSING_VCN);
    }

    @Test
    void okeRejectsAnUnknownEndpointSubnetLoadBalancerSubnetOrNsg() {
        rejects("/20180222/clusters", cluster(Map.of("subnetId", MISSING_SUBNET), List.of()),
                "subnet", MISSING_SUBNET);
        rejects("/20180222/clusters", cluster(Map.of("subnetId", network.subnetId()), List.of(MISSING_SUBNET)),
                "subnet", MISSING_SUBNET);
        rejects("/20180222/clusters",
                cluster(Map.of("subnetId", network.subnetId(), "nsgIds", List.of(MISSING_NSG)), List.of()),
                "networksecuritygroup", MISSING_NSG);
    }

    @Test
    void okeRejectsEndpointOrLoadBalancerSubnetsFromAnotherVcn() {
        TestNetworks.Network other = TestNetworks.create(compartment);

        rejectsForeignSubnet(cluster(Map.of("subnetId", other.subnetId()), List.of()),
                "endpointConfig.subnetId", other.subnetId());
        rejectsForeignSubnet(cluster(Map.of("subnetId", network.subnetId()), List.of(other.subnetId())),
                "options.serviceLbSubnetIds", other.subnetId());
    }

    private static void rejectsForeignSubnet(Object body, String field, String subnetId) {
        given().contentType("application/json").body(body)
            .when().post("/20180222/clusters")
            .then().statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("InvalidParameter"))
                .body("message", equalTo(field + " " + subnetId + " belongs to another VCN"));
    }

    @Test
    void aNetworkSecurityGroupListThatIsNotAListIsInvalid() {
        malformed("/20220915/dbSystems", dbSystem(Map.of("subnetId", network.subnetId(), "nsgIds", MISSING_NSG)),
                "networkDetails.nsgIds");
        malformed("/20180222/clusters", cluster(Map.of("subnetId", network.subnetId(), "nsgIds", MISSING_NSG), List.of()),
                "endpointConfig.nsgIds");
        malformed("/20181201/applications",
                Map.of("compartmentId", compartment, "displayName", "refs-app-malformed",
                        "subnetIds", List.of(network.subnetId()), "networkSecurityGroupIds", MISSING_NSG),
                "networkSecurityGroupIds");
    }

    private static void malformed(String path, Object body, String field) {
        given().contentType("application/json").body(body)
            .when().post(path)
            .then().statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("InvalidParameter"))
                .body("message", equalTo(field + " must be a list of OCIDs"));
    }

    @Test
    void okeAcceptsNetworkResourcesThatExist() {
        given().contentType("application/json")
                .body(cluster(Map.of("subnetId", network.subnetId(), "nsgIds", List.of(network.nsgId())),
                        List.of(network.subnetId())))
            .when().post("/20180222/clusters")
            .then().statusCode(202);
    }

    @Test
    void okeRejectsAnUnknownNsgOnUpdateClusterEndpointConfig() {
        String workRequestId = given().contentType("application/json")
                .body(cluster(Map.of("subnetId", network.subnetId()), List.of()))
            .when().post("/20180222/clusters")
            .then().statusCode(202)
                .extract().header("opc-work-request-id");
        String clusterId = given().when().get("/20180222/workRequests/" + workRequestId)
            .then().statusCode(200)
                .extract().path("resources[0].identifier");

        given().contentType("application/json").body(Map.of("nsgIds", List.of(MISSING_NSG)))
            .when().post("/20180222/clusters/" + clusterId + "/actions/updateEndpointConfig")
            .then().statusCode(400)
                .body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"));
    }

    private static Map<String, Object> cluster(Map<String, Object> endpointConfig, List<String> lbSubnets) {
        Map<String, Object> details = new HashMap<>();
        details.put("compartmentId", compartment);
        details.put("name", "refs-cluster");
        details.put("vcnId", network.vcnId());
        details.put("endpointConfig", endpointConfig);
        details.put("options", Map.of("serviceLbSubnetIds", lbSubnets));
        return details;
    }

    @Test
    void functionsRejectsAnUnknownSubnetOrNsg() {
        rejects("/20181201/applications",
                Map.of("compartmentId", compartment, "displayName", "refs-app", "subnetIds", List.of(MISSING_SUBNET)),
                "subnet", MISSING_SUBNET);
        rejects("/20181201/applications",
                Map.of("compartmentId", compartment, "displayName", "refs-app",
                        "subnetIds", List.of(network.subnetId()), "networkSecurityGroupIds", List.of(MISSING_NSG)),
                "networksecuritygroup", MISSING_NSG);
    }

    @Test
    void functionsRejectsAnUnknownNsgOnUpdate() {
        String applicationId = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "displayName", "refs-app-update",
                        "subnetIds", List.of(network.subnetId()), "networkSecurityGroupIds", List.of(network.nsgId())))
            .when().post("/20181201/applications")
            .then().statusCode(200)
                .extract().path("id");

        given().contentType("application/json").body(Map.of("networkSecurityGroupIds", List.of(MISSING_NSG)))
            .when().put("/20181201/applications/" + applicationId)
            .then().statusCode(400)
                .body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"));

        given().when().get("/20181201/applications/" + applicationId)
            .then().statusCode(200)
                .body("networkSecurityGroupIds[0]", equalTo(network.nsgId()));
        given().when().delete("/20181201/applications/" + applicationId)
            .then().statusCode(204);
    }

    @Test
    void postgresqlRejectsAnUnknownSubnetOrNsg() {
        rejects("/20220915/dbSystems", dbSystem(Map.of("subnetId", MISSING_SUBNET)), "subnet", MISSING_SUBNET);
        rejects("/20220915/dbSystems",
                dbSystem(Map.of("subnetId", network.subnetId(), "nsgIds", List.of(MISSING_NSG))),
                "networksecuritygroup", MISSING_NSG);
    }

    @Test
    void aSubnetFromAnotherRegionIsUnknown() {
        TestRegions.subscribe(config.defaultTenancyId(), "PHX");
        String phoenixSubnet = TestNetworks.createIn("iaas.us-phoenix-1.oraclecloud.com", compartment).subnetId();
        rejects("/20220915/dbSystems", dbSystem(Map.of("subnetId", phoenixSubnet)), "subnet", phoenixSubnet);
    }

    private static Map<String, Object> dbSystem(Map<String, Object> networkDetails) {
        Map<String, Object> details = new HashMap<>();
        details.put("displayName", "refs-db");
        details.put("compartmentId", compartment);
        details.put("dbVersion", "15");
        details.put("shape", "PostgreSQL.VM.Standard.E4.Flex.2.32GB");
        details.put("storageDetails", Map.of("systemType", "OCI_OPTIMIZED_STORAGE", "isRegionallyDurable", true));
        details.put("networkDetails", networkDetails);
        details.put("credentials", Map.of("username", "adminUser",
                "passwordDetails", Map.of("passwordType", "PLAIN_TEXT", "password", "BEstrO0ng_#11")));
        return details;
    }
}
