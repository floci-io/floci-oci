package io.floci.oci.services.dns;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.services.networking.VcnLifecycleListener;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Gives every VCN its dedicated resolver and protected default view, and removes them with the VCN.
 * "VCN-dedicated resolvers are initially created in the same compartment as their corresponding VCN"
 * (oci-python-sdk dns_client.py, change_resolver_compartment). Nothing happens while DNS is disabled;
 * a VCN created then gets its resolver the first time its resolver association is read.
 */
@ApplicationScoped
public class DnsVcnResolvers implements VcnLifecycleListener {

    private final DnsService service;
    private final EmulatorConfig config;

    @Inject
    public DnsVcnResolvers(DnsService service, EmulatorConfig config) {
        this.service = service;
        this.config = config;
    }

    @Override
    public void vcnCreated(Vcn vcn) {
        if (enabled()) {
            service.ensureVcnResolver(vcn.id(), vcn.compartmentId(), vcn.displayName());
        }
    }

    @Override
    public void vcnDeleted(String vcnId) {
        if (enabled()) {
            service.deleteVcnResolver(vcnId);
        }
    }

    @Override
    public Optional<String> dnsResolverId(Vcn vcn) {
        if (!enabled()) {
            return Optional.empty();
        }
        return Optional.of(service.ensureVcnResolver(vcn.id(), vcn.compartmentId(), vcn.displayName()).getId());
    }

    private boolean enabled() {
        return config.services().dns().enabled();
    }
}
