package io.floci.oci.compat;

import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.CreateSubnetDetails;
import com.oracle.bmc.core.model.CreateVcnDetails;
import com.oracle.bmc.core.requests.CreateSubnetRequest;
import com.oracle.bmc.core.requests.CreateVcnRequest;

import java.util.List;

/**
 * A VCN and subnet for the suites whose resources reference one: OKE, Functions and PostgreSQL
 * reject network OCIDs that do not exist.
 */
final class CompatNetworks {

    record Network(String vcnId, String subnetId) {
    }

    private CompatNetworks() {
    }

    static Network create(String compartmentId, String name) {
        try (VirtualNetworkClient network = EmulatorFixture.virtualNetwork()) {
            String vcnId = network.createVcn(CreateVcnRequest.builder()
                    .createVcnDetails(CreateVcnDetails.builder()
                            .compartmentId(compartmentId)
                            .displayName(name + "-vcn")
                            .cidrBlocks(List.of("10.0.0.0/16"))
                            .build())
                    .build()).getVcn().getId();
            String subnetId = network.createSubnet(CreateSubnetRequest.builder()
                    .createSubnetDetails(CreateSubnetDetails.builder()
                            .compartmentId(compartmentId)
                            .vcnId(vcnId)
                            .displayName(name + "-subnet")
                            .cidrBlock("10.0.0.0/24")
                            .build())
                    .build()).getSubnet().getId();
            return new Network(vcnId, subnetId);
        }
    }
}
