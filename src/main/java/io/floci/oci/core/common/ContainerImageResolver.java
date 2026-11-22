package io.floci.oci.core.common;

/**
 * Turns an image reference a client stored on a resource into one the Docker daemon can pull, so
 * services that run containers (Functions) can use images pushed to the local OCIR registry without
 * depending on the Artifacts service. A reference the resolver does not own comes back unchanged.
 */
public interface ContainerImageResolver {

    String pullable(String image);
}
