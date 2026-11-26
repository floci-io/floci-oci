package io.floci.oci.core.common;

import java.util.List;
import java.util.Optional;

/**
 * Turns an image reference a client stored on a resource into one the Docker daemon can pull, so
 * services that run containers (Functions) can use images pushed to the local OCIR registry without
 * depending on the Artifacts service. A reference the resolver does not own comes back unchanged.
 */
public interface ContainerImageResolver {

    String pullable(String image);

    /**
     * How a container runtime that is not the Docker daemon (the containerd inside an OKE k3s
     * sidecar) reaches the local OCIR for one region: the registry hosts an image reference may
     * name, the plain-HTTP endpoint those hosts mirror to, and the path prefix the registry stores
     * that region's images under. Empty when there is no local registry to reach.
     */
    default Optional<RegistryMirror> registryMirror(String region) {
        return Optional.empty();
    }

    record RegistryMirror(List<String> hosts, String endpoint, String pathPrefix) {
    }
}
