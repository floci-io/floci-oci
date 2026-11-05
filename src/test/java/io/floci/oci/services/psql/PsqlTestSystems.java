package io.floci.oci.services.psql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Deletes the DB systems a REST test left behind. Every test class shares one emulator, and the
 * service limit counts DB systems per tenancy and region, not per compartment.
 */
final class PsqlTestSystems {

    private PsqlTestSystems() {
    }

    static void deleteAll(String compartmentId) {
        List<Map<String, Object>> items = given().queryParam("compartmentId", compartmentId)
            .when().get("/20220915/dbSystems")
            .then().statusCode(200).extract().path("items");
        List<String> remaining = new ArrayList<>(items.stream()
                .filter(item -> !"DELETED".equals(item.get("lifecycleState"))
                        && !"DELETING".equals(item.get("lifecycleState")))
                .map(item -> (String) item.get("id"))
                .toList());
        // A primary cannot go while it has replicas, so a pass deletes what it can and retries the rest.
        for (int pass = 0; pass < 3 && !remaining.isEmpty(); pass++) {
            remaining.removeIf(id -> given().when().delete("/20220915/dbSystems/" + id).statusCode() == 202);
        }
        if (!remaining.isEmpty()) {
            throw new IllegalStateException("DB systems left behind in " + compartmentId + ": " + remaining);
        }
    }
}
