package io.floci.oci.services.oke;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Generates the YAML {@code CreateKubeconfig} returns. The default ({@code kubeconfig-auth: exec})
 * is the token version 2.0.0 shape real OKE returns: an {@code exec} user running
 * {@code oci ce cluster generate-token --cluster-id <ocid> --region <region>}
 * (Oracle "Setting Up Cluster Access"; oci-cli {@code CHANGELOG.rst} 2.6.4 and the 1.0.0 removal),
 * with {@code cluster-}/{@code context-}/{@code user-} names as in oci-cli's recorded sample.
 * {@code kubeconfig-auth: token} swaps the user for the cluster's static bearer token instead.
 */
@ApplicationScoped
public class OkeKubeconfigGenerator {

    private static final Logger LOG = Logger.getLogger(OkeKubeconfigGenerator.class);
    static final String AUTH_TOKEN = "token";

    private final EmulatorConfig config;

    @Inject
    public OkeKubeconfigGenerator(EmulatorConfig config) {
        this.config = config;
    }

    public String generateKubeconfig(StoredOkeCluster cluster) {
        String endpoint = cluster.getEndpoints() != null && cluster.getEndpoints().containsKey("kubernetes")
                ? cluster.getEndpoints().get("kubernetes")
                : "https://127.0.0.1:6443";
        String id = shortId(cluster.getId());

        return """
               apiVersion: v1
               clusters:
               - cluster:
                   %s
                   server: %s
                 name: cluster-%s
               contexts:
               - context:
                   cluster: cluster-%s
                   user: user-%s
                 name: context-%s
               current-context: context-%s
               kind: Config
               preferences: {}
               users:
               - name: user-%s
                 user:
               %s""".formatted(trust(cluster), endpoint, id, id, id, id, id, id, user(cluster));
    }

    private String trust(StoredOkeCluster cluster) {
        if (cluster.getCaCertificate() != null) {
            return "certificate-authority-data: " + cluster.getCaCertificate();
        }
        LOG.debugv("OKE cluster {0} has no captured CA yet; kubeconfig skips TLS verification", cluster.getId());
        return "insecure-skip-tls-verify: true";
    }

    private String user(StoredOkeCluster cluster) {
        if (AUTH_TOKEN.equalsIgnoreCase(config.services().oke().kubeconfigAuth())) {
            return "    token: " + cluster.getApiToken() + "\n";
        }
        String region = cluster.getRegion() != null ? cluster.getRegion() : config.defaultRegion();
        return """
                   exec:
                     apiVersion: client.authentication.k8s.io/v1beta1
                     args:
                     - ce
                     - cluster
                     - generate-token
                     - --cluster-id
                     - %s
                     - --region
                     - %s
                     command: oci
                     env: []
                     interactiveMode: IfAvailable
                     provideClusterInfo: false
               """.formatted(cluster.getId(), region);
    }

    /** The OCID's unique segment, used to name the kubeconfig's cluster, context and user. */
    static String shortId(String clusterId) {
        return clusterId.substring(clusterId.lastIndexOf('.') + 1);
    }
}
