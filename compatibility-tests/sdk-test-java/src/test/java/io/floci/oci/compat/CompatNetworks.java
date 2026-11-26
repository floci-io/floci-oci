package io.floci.oci.compat;

import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.CreateSubnetDetails;
import com.oracle.bmc.core.model.CreateVcnDetails;
import com.oracle.bmc.core.requests.CreateSubnetRequest;
import com.oracle.bmc.core.requests.CreateVcnRequest;
import com.oracle.bmc.core.requests.DeleteSubnetRequest;
import com.oracle.bmc.core.requests.DeleteVcnRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * A VCN and subnet for the suites whose resources reference one: OKE, Functions and PostgreSQL
 * reject network OCIDs that do not exist. Each suite calls {@link #deleteAll()} from its
 * {@code @AfterAll}, after the resources that use them are gone.
 */
final class CompatNetworks {

    record Network(String vcnId, String subnetId) {
    }

    private static final List<Network> CREATED = new ArrayList<>();

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
            Network created = new Network(vcnId, subnetId);
            synchronized (CREATED) {
                CREATED.add(created);
            }
            return created;
        }
    }

    /** Deletes every network {@link #create} made, each subnet before its VCN. */
    static void deleteAll() {
        List<Network> networks;
        synchronized (CREATED) {
            networks = new ArrayList<>(CREATED);
            CREATED.clear();
        }
        try (VirtualNetworkClient network = EmulatorFixture.virtualNetwork()) {
            for (Network created : networks) {
                network.deleteSubnet(DeleteSubnetRequest.builder().subnetId(created.subnetId()).build());
                network.deleteVcn(DeleteVcnRequest.builder().vcnId(created.vcnId()).build());
            }
        }
    }
}
