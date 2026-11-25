package io.floci.oci.testing;

import io.restassured.specification.RequestSpecification;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Network setup for REST tests: OKE, Functions and PostgreSQL only accept a VCN, subnet or network
 * security group that exists.
 */
public final class TestNetworks {

    /** A VCN with one subnet and one network security group, all in one compartment. */
    public record Network(String vcnId, String subnetId, String nsgId) {
    }

    private TestNetworks() {
    }

    public static Network create(String compartmentId) {
        return createIn(null, compartmentId);
    }

    /** The same network in the region a Host header names (null for the default region), e.g. iaas.us-phoenix-1.oraclecloud.com. */
    public static Network createIn(String host, String compartmentId) {
        String vcnId = request(host)
                .body(Map.of("compartmentId", compartmentId, "cidrBlocks", List.of("10.0.0.0/16"),
                        "displayName", "test-vcn"))
            .when().post("/20160918/vcns")
            .then().statusCode(200)
                .extract().path("id");
        String subnetId = subnetIn(host, compartmentId, vcnId, "10.0.0.0/24");
        String nsgId = request(host)
                .body(Map.of("compartmentId", compartmentId, "vcnId", vcnId, "displayName", "test-nsg"))
            .when().post("/20160918/networkSecurityGroups")
            .then().statusCode(200)
                .extract().path("id");
        return new Network(vcnId, subnetId, nsgId);
    }

    /** Another subnet in an existing VCN; its CIDR must not overlap the VCN's other subnets. */
    public static String subnet(String compartmentId, String vcnId, String cidrBlock) {
        return subnetIn(null, compartmentId, vcnId, cidrBlock);
    }

    private static String subnetIn(String host, String compartmentId, String vcnId, String cidrBlock) {
        return request(host)
                .body(Map.of("compartmentId", compartmentId, "vcnId", vcnId, "cidrBlock", cidrBlock,
                        "displayName", "test-subnet"))
            .when().post("/20160918/subnets")
            .then().statusCode(200)
                .extract().path("id");
    }

    /** A JSON request, sent to the region the Host header names, or the default region for null. */
    private static RequestSpecification request(String host) {
        RequestSpecification request = given().contentType("application/json");
        return host != null ? request.header("Host", host) : request;
    }
}
