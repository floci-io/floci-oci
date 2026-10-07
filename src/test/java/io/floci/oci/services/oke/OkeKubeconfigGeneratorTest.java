package io.floci.oci.services.oke;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OkeKubeconfigGeneratorTest {

    private EmulatorConfig config;
    private OkeKubeconfigGenerator generator;
    private StoredOkeCluster cluster;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().oke().kubeconfigAuth()).thenReturn("exec");
        when(config.defaultRegion()).thenReturn("us-ashburn-1");
        generator = new OkeKubeconfigGenerator(config);

        cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.aaaabbbbcccc");
        cluster.setRegion("us-phoenix-1");
        cluster.setApiToken("static-token");
        cluster.setCaCertificate("Q0FEQVRB");
        cluster.setEndpoints(Map.of("kubernetes", "https://127.0.0.1:6450"));
    }

    @Test
    void execModeIsTheRealOkeTokenVersion2Kubeconfig() {
        assertEquals("""
                apiVersion: v1
                clusters:
                - cluster:
                    certificate-authority-data: Q0FEQVRB
                    server: https://127.0.0.1:6450
                  name: cluster-aaaabbbbcccc
                contexts:
                - context:
                    cluster: cluster-aaaabbbbcccc
                    user: user-aaaabbbbcccc
                  name: context-aaaabbbbcccc
                current-context: context-aaaabbbbcccc
                kind: Config
                preferences: {}
                users:
                - name: user-aaaabbbbcccc
                  user:
                    exec:
                      apiVersion: client.authentication.k8s.io/v1beta1
                      args:
                      - ce
                      - cluster
                      - generate-token
                      - --cluster-id
                      - ocid1.cluster.oc1.iad.aaaabbbbcccc
                      - --region
                      - us-phoenix-1
                      command: oci
                      env: []
                      interactiveMode: IfAvailable
                      provideClusterInfo: false
                """, generator.generateKubeconfig(cluster));
    }

    @Test
    void tokenModeUsesTheClusterBearerToken() {
        when(config.services().oke().kubeconfigAuth()).thenReturn("token");

        String kubeconfig = generator.generateKubeconfig(cluster);

        assertTrue(kubeconfig.endsWith("""
                users:
                - name: user-aaaabbbbcccc
                  user:
                    token: static-token
                """), kubeconfig);
        assertFalse(kubeconfig.contains("exec:"));
    }

    @Test
    void withoutACapturedCaItSkipsTlsVerification() {
        cluster.setCaCertificate(null);

        String kubeconfig = generator.generateKubeconfig(cluster);

        assertTrue(kubeconfig.contains("""
                - cluster:
                    insecure-skip-tls-verify: true
                    server: https://127.0.0.1:6450
                """), kubeconfig);
        assertFalse(kubeconfig.contains("certificate-authority-data"));
    }

    @Test
    void clustersStoredWithoutARegionUseTheDefaultRegion() {
        cluster.setRegion(null);

        assertTrue(generator.generateKubeconfig(cluster).contains("""
                      - --region
                      - us-ashburn-1
                """));
    }
}
