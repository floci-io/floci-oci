package io.floci.oci.services.objectstorage;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(ObjectStorageStrictNamespaceRestIntegrationTest.StrictNamespaceProfile.class)
class ObjectStorageStrictNamespaceRestIntegrationTest {

    private static final String NS = "floci-test";
    private static final String COMPARTMENT = "ocid1.compartment.oc1..ittestcompartment";

    @Test
    void ownNamespaceIsServed() {
        given().queryParam("compartmentId", COMPARTMENT)
            .when().get("/n/" + NS + "/b")
            .then().statusCode(200);
    }

    @Test
    void anotherNamespaceIsNamespaceNotFound() {
        given().queryParam("compartmentId", COMPARTMENT)
            .when().get("/n/someoneelses/b")
            .then().statusCode(404)
                .header("opc-request-id", notNullValue())
                .body("code", equalTo("NamespaceNotFound"))
                .body("message", equalTo(
                        "Either the namespace someoneelses does not exist or you are not authorized to access it."));
    }

    @Test
    void namespaceLookupIsNotChecked() {
        given()
            .when().get("/n")
            .then().statusCode(200).body(equalTo("\"" + NS + "\""));
    }

    public static class StrictNamespaceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-oci.services.objectstorage.strict-namespace", "true");
        }
    }
}
