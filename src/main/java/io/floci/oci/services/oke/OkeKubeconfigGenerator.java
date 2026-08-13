package io.floci.oci.services.oke;

import io.floci.oci.services.oke.model.StoredOkeCluster;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Generates Kubernetes Kubeconfig YAML payloads for OKE clusters.
 */
@ApplicationScoped
public class OkeKubeconfigGenerator {

    /**
     * Generates a raw YAML kubeconfig stream for the target cluster.
     *
     * @param cluster target OKE cluster
     * @param tokenType token generation type (e.g. BASIC or STRUCTURED_TOKEN)
     * @return String containing YAML content
     */
    public String generateKubeconfig(StoredOkeCluster cluster, String tokenType) {
        String name = cluster.getName() != null ? cluster.getName() : "oke-cluster";
        String endpoint = (cluster.getEndpoints() != null && cluster.getEndpoints().containsKey("kubernetes"))
                ? cluster.getEndpoints().get("kubernetes")
                : "https://127.0.0.1:6443";

        String userToken = "floci-oke-token-" + cluster.getId();

        return """
               apiVersion: v1
               clusters:
               - cluster:
                   insecure-skip-tls-verify: true
                   server: %s
                 name: %s
               contexts:
               - context:
                   cluster: %s
                   user: %s-user
                 name: %s-context
               current-context: %s-context
               kind: Config
               preferences: {}
               users:
               - name: %s-user
                 user:
                   token: %s
               """.formatted(endpoint, name, name, name, name, name, name, userToken);
    }
}
