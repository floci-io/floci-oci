package io.floci.oci.core.common;

/**
 * Implemented by a service whose containers pull from the local OCIR through a
 * {@link ContainerImageResolver.RegistryMirror} (the containerd inside an OKE k3s sidecar), so
 * Artifacts can tell it the mirror endpoint moved without knowing that service.
 */
public interface RegistryMirrorListener {

    /**
     * The registry sidecar came back at another address than the one earlier mirrors point to
     * (it restarted on another port or IP, for example after an Artifacts state reset). Called on
     * the thread that restarted the registry, which may be serving a request: implementations
     * must hand any Docker work to a thread of their own and return at once.
     */
    void registryMirrorMoved();
}
