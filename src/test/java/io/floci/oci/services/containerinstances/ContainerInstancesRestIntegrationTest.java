package io.floci.oci.services.containerinstances;

import io.floci.oci.testing.TestCompartments;
import io.floci.oci.testing.TestNetworks;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ContainerInstancesRestIntegrationTest {

    private static final String AD = "Floc:US-ASHBURN-AD-1";

    private static String compartment;
    private static TestNetworks.Network network;

    @BeforeEach
    void createNetwork() {
        if (compartment == null) {
            compartment = TestCompartments.create("itcontainerinstances");
            network = TestNetworks.create(compartment);
        }
    }

    private static Map<String, Object> details(String name) {
        Map<String, Object> details = new HashMap<>();
        details.put("compartmentId", compartment);
        details.put("availabilityDomain", AD);
        details.put("displayName", name);
        details.put("shape", "CI.Standard.E4.Flex");
        details.put("shapeConfig", Map.of("ocpus", 1, "memoryInGBs", 4));
        details.put("containers", List.of(Map.of("imageUrl", "busybox:1.36", "displayName", "web",
                "arguments", List.of("httpd", "-f"),
                "healthChecks", List.of(Map.of("healthCheckType", "HTTP", "path", "/", "port", 80)),
                "volumeMounts", List.of(Map.of("volumeName", "scratch", "mountPath", "/data")))));
        details.put("volumes", List.of(Map.of("name", "scratch", "volumeType", "EMPTYDIR")));
        details.put("vnics", List.of(Map.of("subnetId", network.subnetId(), "nsgIds", List.of(network.nsgId()),
                "hostnameLabel", "web")));
        return details;
    }

    private static Response create(String name) {
        return given().contentType("application/json").body(details(name))
            .when().post("/20210415/containerInstances")
            .then().statusCode(200).extract().response();
    }

    @Test
    void createReturnsTheInstanceWithItsEtagAndACreatedWorkRequest() {
        Response created = given().contentType("application/json").body(details("it-create"))
            .when().post("/20210415/containerInstances")
            .then()
                .statusCode(200)
                .header("opc-request-id", notNullValue())
                .header("etag", notNullValue())
                .header("opc-work-request-id", notNullValue())
                .body("id", startsWith("ocid1.computecontainerinstance."))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("availabilityDomain", equalTo(AD))
                .body("faultDomain", equalTo("FAULT-DOMAIN-1"))
                .body("containerCount", equalTo(1))
                .body("containers[0].displayName", equalTo("web"))
                .body("vnics[0].vnicId", startsWith("ocid1.vnic."))
                .body("shapeConfig.ocpus", equalTo(1.0f))
                .body("shapeConfig.memoryInGBs", equalTo(4.0f))
                .body("shapeConfig.processorDescription", notNullValue())
                .body("containerRestartPolicy", equalTo("ALWAYS"))
                .body("volumes[0].backingStore", equalTo("EPHEMERAL_STORAGE"))
                .body("volumeCount", equalTo(1))
                .extract().response();

        given().when().get("/20210415/workRequests/" + created.header("opc-work-request-id"))
            .then().statusCode(200)
                .body("operationType", equalTo("CREATE_CONTAINER_INSTANCE"))
                .body("status", equalTo("SUCCEEDED"))
                .body("resources[0].entityType", equalTo("containerInstance"))
                .body("resources[0].actionType", equalTo("CREATED"))
                .body("resources[0].identifier", equalTo(created.path("id")))
                .body("$", not(hasKey("errors")));
    }

    @Test
    void theContainerAndTheVnicReadBackWhatWasSent() {
        Response created = create("it-readback");
        String containerId = created.path("containers[0].containerId");
        String vnicId = created.path("vnics[0].vnicId");

        given().when().get("/20210415/containers/" + containerId)
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("containerInstanceId", equalTo(created.path("id")))
                .body("imageUrl", equalTo("busybox:1.36"))
                .body("arguments", contains("httpd", "-f"))
                .body("volumeMounts[0].mountPath", equalTo("/data"))
                .body("healthChecks[0].healthCheckType", equalTo("HTTP"))
                .body("healthChecks[0].status", equalTo("UNKNOWN"))
                .body("isResourcePrincipalDisabled", equalTo(false))
                .body("lifecycleState", equalTo("ACTIVE"));

        given().when().get("/20160918/vnics/" + vnicId)
            .then().statusCode(200)
                .header("etag", notNullValue())
                .body("subnetId", equalTo(network.subnetId()))
                .body("availabilityDomain", equalTo(AD))
                .body("hostnameLabel", equalTo("web"))
                .body("nsgIds", contains(network.nsgId()))
                .body("privateIp", startsWith("10.0.0."))
                .body("isPrimary", equalTo(true))
                .body("skipSourceDestCheck", equalTo(false))
                .body("lifecycleState", equalTo("AVAILABLE"))
                .body("$", not(hasKey("publicIp")))
                .body("$", not(hasKey("vcnId")));

        given().when().get("/20160918/networkSecurityGroups/" + network.nsgId() + "/vnics")
            .then().statusCode(200)
                .body("vnicId", hasItem(vnicId));
    }

    @Test
    void listsAreItemCollections() {
        String id = create("it-list").path("id");

        given().queryParam("compartmentId", compartment).queryParam("displayName", "it-list")
            .when().get("/20210415/containerInstances")
            .then().statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].id", equalTo(id))
                .body("items[0]", not(hasKey("containers")))
                .body("items[0]", not(hasKey("vnics")));

        given().queryParam("compartmentId", compartment).queryParam("containerInstanceId", id)
            .when().get("/20210415/containers")
            .then().statusCode(200)
                .body("items", hasSize(1))
                .body("items[0]", not(hasKey("arguments")));

        given().queryParam("compartmentId", compartment)
            .when().get("/20210415/containerInstanceShapes")
            .then().statusCode(200)
                .body("items.name", hasItems("CI.Standard.E4.Flex", "CI.Standard.A1.Flex"))
                .body("items.name", not(hasItem("CI.Standard.E5.Flex")))
                .body("items[0].ocpuOptions.max", notNullValue());

        given().queryParam("compartmentId", compartment).queryParam("resourceId", id)
            .when().get("/20210415/workRequests")
            .then().statusCode(200)
                .body("items[0].resources[0].identifier", equalTo(id));
    }

    @Test
    void actionsAnswer202WithAWorkRequest() {
        Response created = create("it-actions");
        String id = created.path("id");

        given().contentType("application/json").body(Map.of("displayName", "it-actions-renamed"))
            .when().put("/20210415/containerInstances/" + id)
            .then().statusCode(202).header("opc-work-request-id", notNullValue());

        given().when().post("/20210415/containerInstances/" + id + "/actions/stop")
            .then().statusCode(202).header("opc-work-request-id", notNullValue());
        given().when().get("/20210415/containerInstances/" + id)
            .then().statusCode(200)
                .body("displayName", equalTo("it-actions-renamed"))
                .body("lifecycleState", equalTo("INACTIVE"));

        given().when().post("/20210415/containerInstances/" + id + "/actions/stop")
            .then().statusCode(409).body("code", equalTo("IncorrectState"));

        given().when().post("/20210415/containerInstances/" + id + "/actions/start")
            .then().statusCode(202);
        given().when().post("/20210415/containerInstances/" + id + "/actions/restart")
            .then().statusCode(202);

        given().contentType("application/json").body(Map.of("displayName", "renamed-container"))
            .when().put("/20210415/containers/" + created.path("containers[0].containerId"))
            .then().statusCode(202).header("opc-work-request-id", notNullValue());

        given().when().post("/20210415/containers/" + created.path("containers[0].containerId")
                + "/actions/retrieveLogs")
            .then().statusCode(200).contentType(startsWith("text/plain"));

        String vnicId = created.path("vnics[0].vnicId");
        given().when().delete("/20210415/containerInstances/" + id)
            .then().statusCode(202).header("opc-work-request-id", notNullValue());
        given().when().get("/20210415/containerInstances/" + id)
            .then().statusCode(200).body("lifecycleState", equalTo("DELETED"));
        given().when().get("/20160918/vnics/" + vnicId)
            .then().statusCode(404).body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void aSubnetWithAContainerInstanceCannotBeDeleted() {
        String subnet = TestNetworks.subnet(compartment, network.vcnId(), "10.0.5.0/24");
        Map<String, Object> d = details("it-subnet");
        d.put("vnics", List.of(Map.of("subnetId", subnet)));
        String id = given().contentType("application/json").body(d)
            .when().post("/20210415/containerInstances")
            .then().statusCode(200).extract().path("id");

        given().when().delete("/20160918/subnets/" + subnet)
            .then().statusCode(409).body("code", equalTo("Conflict"));

        given().when().delete("/20210415/containerInstances/" + id).then().statusCode(202);
        given().when().delete("/20160918/subnets/" + subnet).then().statusCode(204);
    }

    @Test
    void aNetworkSecurityGroupWithAContainerInstanceCannotBeDeleted() {
        String nsg = given().contentType("application/json")
                .body(Map.of("compartmentId", compartment, "vcnId", network.vcnId(), "displayName", "it-nsg"))
            .when().post("/20160918/networkSecurityGroups")
            .then().statusCode(200).extract().path("id");
        Map<String, Object> d = details("it-nsg");
        d.put("vnics", List.of(Map.of("subnetId", network.subnetId(), "nsgIds", List.of(nsg))));
        String id = given().contentType("application/json").body(d)
            .when().post("/20210415/containerInstances")
            .then().statusCode(200).extract().path("id");

        given().when().delete("/20160918/networkSecurityGroups/" + nsg)
            .then().statusCode(409)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("Conflict"))
                .body("message", equalTo("Network security group " + nsg + " still contains VNICs."));

        given().when().delete("/20210415/containerInstances/" + id).then().statusCode(202);
        given().when().delete("/20160918/networkSecurityGroups/" + nsg)
            .then().statusCode(204)
                .header("opc-request-id", notNullValue());
    }

    @Test
    void unknownReferencesAndBadInputAreRejected() {
        Map<String, Object> d = details("it-bad");
        d.put("vnics", List.of(Map.of("subnetId", "ocid1.subnet.oc1.iad.doesnotexist")));
        given().contentType("application/json").body(d)
            .when().post("/20210415/containerInstances")
            .then().statusCode(400)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("RelatedResourceNotAuthorizedOrNotFound"));

        Map<String, Object> badShape = details("it-bad");
        badShape.put("shape", "VM.Standard.E4.Flex");
        given().contentType("application/json").body(badShape)
            .when().post("/20210415/containerInstances")
            .then().statusCode(400).body("code", equalTo("InvalidParameter"));

        given().when().get("/20210415/containerInstances/ocid1.computecontainerinstance.oc1.iad.missing")
            .then().statusCode(404)
                .body("code", equalTo("NotAuthorizedOrNotFound"));
    }

    @Test
    void updateVnicChangesItsNamesAndChecksIfMatch() {
        String vnicId = create("it-vnic").path("vnics[0].vnicId");
        String etag = given().when().get("/20160918/vnics/" + vnicId).then().statusCode(200).extract().header("etag");

        given().contentType("application/json").header("if-match", "stale").body(Map.of("displayName", "x"))
            .when().put("/20160918/vnics/" + vnicId)
            .then().statusCode(412);
        given().contentType("application/json").header("if-match", etag)
                .body(Map.of("displayName", "renamed-vnic", "skipSourceDestCheck", true))
            .when().put("/20160918/vnics/" + vnicId)
            .then().statusCode(200)
                .header("etag", not(equalTo(etag)))
                .body("displayName", equalTo("renamed-vnic"))
                .body("skipSourceDestCheck", equalTo(true));
    }
}
