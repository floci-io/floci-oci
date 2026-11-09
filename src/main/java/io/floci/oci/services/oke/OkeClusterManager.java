package io.floci.oci.services.oke;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.Resettable;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerDetector;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.core.common.docker.CurrentContainerNetworkResolver;
import io.floci.oci.core.common.docker.DockerHostResolver;
import io.floci.oci.core.common.docker.PortAllocator;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Driver for real-mode rancher/k3s Docker sidecar containers managing local Kubernetes clusters.
 */
@ApplicationScoped
public class OkeClusterManager implements Resettable {

    private static final Logger LOG = Logger.getLogger(OkeClusterManager.class);
    private static final int K3S_CONTAINER_PORT = 6443;
    static final String API_TOKEN_ENV = "FLOCI_OKE_API_TOKEN";
    static final String WEBHOOK_KUBECONFIG_ENV = "FLOCI_OKE_WEBHOOK_KUBECONFIG";
    static final String TLS_SAN_ENV = "FLOCI_OKE_TLS_SAN";
    static final String ENDPOINT_MODE_HOST = "host";
    static final String ENDPOINT_MODE_NETWORK = "network";
    /** Docker's built-in networks, which have no container-name lookup. */
    private static final Set<String> NETWORKS_WITHOUT_NAME_LOOKUP = Set.of("bridge", "host", "none");
    static final String TOKEN_DIR = "/var/lib/rancher/k3s/floci";
    static final String TOKEN_FILE = TOKEN_DIR + "/tokens.csv";
    static final String WEBHOOK_FILE = TOKEN_DIR + "/token-webhook.yaml";
    static final String WEBHOOK_PATH_PREFIX = "/_floci-oci/oke/token-webhook/";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    /**
     * Writes the k3s static token file from {@link #API_TOKEN_ENV} and the token-webhook
     * kubeconfig from {@link #WEBHOOK_KUBECONFIG_ENV}, then execs k3s with the container CMD plus a
     * serving-certificate SAN for the container name in {@link #TLS_SAN_ENV}, so
     * {@code endpoint-mode=network} endpoints verify. k3s adds a new SAN to the certificate it keeps
     * on the volume when it restarts, so a recreated sidecar keeps its CA.
     * Both files are rewritten from the container's env on every start; the webhook address in
     * that env is fixed when the container is created. The image's own CMD is {@code agent}, which needs a server to join
     * and exits at once, so {@link #buildCmd} always supplies {@code server}. Values travel as env
     * vars so nothing from the cluster record is ever interpolated into the script.
     */
    static final List<String> K3S_ENTRYPOINT = List.of("sh", "-c",
            "umask 077 && mkdir -p " + TOKEN_DIR
                    + " && printf '%s,floci-admin,floci-admin,system:masters\\n' \"$" + API_TOKEN_ENV + "\""
                    + " > " + TOKEN_FILE
                    + " && printf '%s\\n' \"$" + WEBHOOK_KUBECONFIG_ENV + "\""
                    + " > " + WEBHOOK_FILE
                    + " && exec /bin/k3s \"$@\" --tls-san=\"$" + TLS_SAN_ENV + "\"");

    public record ActiveClusterRef(String containerName, String volumeName, int hostPort) {}

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final PortAllocator portAllocator;
    private final DockerHostResolver dockerHostResolver;
    private final ContainerDetector containerDetector;
    private final CurrentContainerNetworkResolver networkResolver;
    private final EmulatorConfig config;

    private final Map<String, ActiveClusterRef> activeClusters = new ConcurrentHashMap<>();

    @Inject
    public OkeClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             PortAllocator portAllocator,
                             DockerHostResolver dockerHostResolver,
                             ContainerDetector containerDetector,
                             CurrentContainerNetworkResolver networkResolver,
                             EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.portAllocator = portAllocator;
        this.dockerHostResolver = dockerHostResolver;
        this.containerDetector = containerDetector;
        this.networkResolver = networkResolver;
        this.config = config;
    }

    /**
     * Starts (or adopts) the k3s sidecar and sets the cluster endpoints. In real mode the
     * lifecycle state is left to the caller: the cluster becomes ACTIVE only once
     * {@link #probeReady} succeeds. Mock mode marks it ACTIVE at once.
     */
    public void startCluster(StoredOkeCluster cluster) {
        if (config.services().oke().mock()) {
            cluster.setLifecycleState("ACTIVE");
            return;
        }
        if (cluster.getApiToken() == null || cluster.getApiToken().isBlank()) {
            throw new IllegalStateException("OKE cluster " + cluster.getId() + " has no API token");
        }

        int hostPort = cluster.getHostPort();
        if (hostPort > 0) {
            portAllocator.markReserved(hostPort);
        } else {
            int basePort = config.services().oke().apiServerBasePort();
            int maxPort = config.services().oke().apiServerMaxPort();
            hostPort = portAllocator.allocate(basePort, maxPort);
            cluster.setHostPort(hostPort);
        }

        String containerName = containerName(cluster);
        String volumeName = ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId());

        activeClusters.put(cluster.getId(), new ActiveClusterRef(containerName, volumeName, hostPort));

        // A volume from an earlier start holds the cluster's data and CA: a failed start removes the
        // volume only when it was confirmed absent before this start created it.
        boolean createdVolume = false;
        try {
            if (!lifecycleManager.isContainerRunning(containerName) || !createdForThisCluster(containerName, cluster)) {
                createdVolume = lifecycleManager.volumeConfirmedAbsent(volumeName);
                lifecycleManager.removeIfExists(containerName);
                // Created up front so it carries the floci labels; a mount alone creates it bare.
                lifecycleManager.ensureVolume(volumeName);

                ContainerSpec spec = containerBuilder.newContainer(config.services().oke().defaultImage())
                        .withName(containerName)
                        .withEntrypoint(K3S_ENTRYPOINT)
                        .withCmd(buildCmd())
                        .withEnv("K3S_KUBECONFIG_MODE", "644")
                        .withEnv(API_TOKEN_ENV, cluster.getApiToken())
                        .withEnv(WEBHOOK_KUBECONFIG_ENV, webhookKubeconfig(cluster))
                        .withEnv(TLS_SAN_ENV, containerName)
                        .withPortBinding(K3S_CONTAINER_PORT, hostPort)
                        .withNamedVolume(volumeName, "/var/lib/rancher/k3s")
                        .withDockerNetwork(Optional.empty())
                        .withHostDockerInternalOnLinux()
                        .withPrivileged(true)
                        .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                                "oke", cluster.getId(), cluster.getCompartmentId(), clusterRegion(cluster)))
                        .build();

                lifecycleManager.createAndStart(spec);
            }
        } catch (Exception e) {
            activeClusters.remove(cluster.getId());
            try {
                lifecycleManager.removeIfExists(containerName);
                if (createdVolume) {
                    lifecycleManager.removeVolume(volumeName);
                }
            } catch (Exception cleanupEx) {
                LOG.warnf(cleanupEx, "Failed to clean up container '%s' or volume '%s' after startup failure", containerName, volumeName);
            }
            portAllocator.release(hostPort);
            cluster.setHostPort(0);
            cluster.setLifecycleState("FAILED");
            LOG.errorf("Failed to start k3s sidecar container '%s' on port %d: %s", containerName, hostPort, e.getMessage());
            throw e;
        }

        cluster.setEndpoints(Map.of(
            "kubernetes", kubernetesEndpoint(containerName, hostPort),
            "privateEndpoint", "10.0.0.10:" + hostPort
        ));
        LOG.infof("Started k3s sidecar container '%s' bound to host port %d", containerName, hostPort);
    }

    /**
     * The API server address {@code endpoints.kubernetes} and the kubeconfig advertise. In
     * {@code endpoint-mode=network}, when floci-oci itself runs in a container, it is the sidecar's
     * container name on the Docker network they share, so other containers on that network reach it;
     * otherwise, and always when floci-oci runs on the host, it is the published host port. A name
     * needs a user-defined network to resolve: on Docker's default {@code bridge} (or {@code host},
     * {@code none}) peers cannot look it up, so network mode falls back to the host port there too.
     */
    String kubernetesEndpoint(String containerName, int hostPort) {
        if (containerDetector.isRunningInContainer()
                && ENDPOINT_MODE_NETWORK.equalsIgnoreCase(config.services().oke().endpointMode())) {
            Optional<String> network = sidecarNetwork();
            if (network.isPresent() && !NETWORKS_WITHOUT_NAME_LOOKUP.contains(network.get())) {
                return "https://" + containerName + ":" + K3S_CONTAINER_PORT;
            }
            LOG.warnv("endpoint-mode=network needs floci-oci on a user-defined Docker network so other containers"
                    + " can resolve {0}, but its sidecar network is {1}; advertising the host port instead",
                    containerName, network.orElse("unknown"));
        }
        return "https://127.0.0.1:" + hostPort;
    }

    static void validateEndpointMode(String mode) {
        if (!ENDPOINT_MODE_HOST.equalsIgnoreCase(mode) && !ENDPOINT_MODE_NETWORK.equalsIgnoreCase(mode)) {
            throw new IllegalStateException("Invalid floci-oci.services.oke.endpoint-mode value '" + mode
                    + "': allowed values are " + ENDPOINT_MODE_HOST + " and " + ENDPOINT_MODE_NETWORK);
        }
    }

    /**
     * Tokens k3s does not know (the {@code oci ce cluster generate-token} ones) go to
     * floci-oci's token webhook. {@code 127.0.0.1} and {@code localhost} are already in k3s's
     * default certificate SANs, which covers the host-mode endpoint; {@link #K3S_ENTRYPOINT} adds the
     * container name for network mode.
     */
    static List<String> buildServerArgs() {
        return List.of("server",
                "--disable=traefik",
                "--kube-apiserver-arg=token-auth-file=" + TOKEN_FILE,
                "--kube-apiserver-arg=authentication-token-webhook-config-file=" + WEBHOOK_FILE,
                "--kube-apiserver-arg=authentication-token-webhook-version=v1",
                "--kube-apiserver-arg=authentication-token-webhook-cache-ttl=30s");
    }

    /**
     * The CMD paired with {@link #K3S_ENTRYPOINT}: a {@code $0} placeholder, then the k3s
     * server args that the script's {@code "$@"} expands to.
     */
    static List<String> buildCmd() {
        List<String> cmd = new ArrayList<>();
        cmd.add("k3s");
        cmd.addAll(buildServerArgs());
        return cmd;
    }

    /**
     * A running sidecar is adopted only when it was created with this cluster's token, current
     * webhook address, container-name SAN and region label. One left by an older floci-oci, or by
     * one reachable at another address, would reject the exec credentials, serve a certificate
     * without the network-mode name, or stay labelled with the wrong region, so it is recreated on
     * the same volume, keeping its CA.
     */
    private boolean createdForThisCluster(String containerName, StoredOkeCluster cluster) {
        List<String> env = lifecycleManager.containerEnv(containerName);
        return env.contains(API_TOKEN_ENV + "=" + cluster.getApiToken())
                && env.contains(WEBHOOK_KUBECONFIG_ENV + "=" + webhookKubeconfig(cluster))
                && env.contains(TLS_SAN_ENV + "=" + containerName)
                && clusterRegion(cluster).equals(
                        lifecycleManager.containerLabels(containerName).get(ContainerStorageHelper.REGION_LABEL));
    }

    /**
     * The kubeconfig k3s's API server uses to POST {@code TokenReview}s to floci-oci. The scope
     * (tenancy, cluster) is in the path, never the query: client-go replaces a server URL's query
     * when it builds the request. Plain http, as floci-aws does: floci-oci keeps serving HTTP on
     * its port even with TLS enabled, and this channel never leaves the Docker host.
     */
    String webhookKubeconfig(StoredOkeCluster cluster) {
        String url = "http://" + dockerHostResolver.resolve() + ":" + config.port() + webhookPath(cluster);
        return """
                apiVersion: v1
                kind: Config
                clusters:
                - name: floci-oci-token-webhook
                  cluster:
                    server: %s
                users:
                - name: floci-oci-token-webhook
                contexts:
                - name: floci-oci-token-webhook
                  context:
                    cluster: floci-oci-token-webhook
                    user: floci-oci-token-webhook
                current-context: floci-oci-token-webhook
                """.formatted(url);
    }

    static String webhookPath(StoredOkeCluster cluster) {
        return WEBHOOK_PATH_PREFIX + cluster.getTenancyId() + "/" + cluster.getId();
    }

    /** Whether the cluster's sidecar container is running. */
    public boolean isClusterRunning(StoredOkeCluster cluster) {
        return lifecycleManager.isContainerRunning(containerName(cluster));
    }

    /**
     * One readiness probe. Fetches the server CA from k3s's unauthenticated {@code /cacerts}
     * (the same bootstrap k3s agents use, so it cannot be verified yet), then calls
     * {@code /readyz} with the cluster's static token, verifying the certificate chain against
     * that CA. Returns the CA as base64 PEM, the form a kubeconfig's
     * {@code certificate-authority-data} takes, once both answer 200.
     */
    public Optional<String> probeReady(StoredOkeCluster cluster) {
        try {
            String base = apiServerUrl(cluster);
            Optional<String> caPem = get(tlsContext(new TrustAnyCertificate()), base + "/cacerts", null);
            if (caPem.isEmpty()) {
                return Optional.empty();
            }
            Optional<String> ready = get(tlsContext(new TrustClusterCa(caPem.get())), base + "/readyz",
                    cluster.getApiToken());
            if (ready.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(Base64.getEncoder().encodeToString(caPem.get().getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | GeneralSecurityException e) {
            LOG.debugv("k3s API for cluster {0} not ready: {1}", cluster.getId(), e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Where floci-oci itself reaches the API server: the published host port when floci-oci runs
     * on the host; otherwise the sidecar's IP on the network it was attached to, read from the
     * container rather than looked up by name. The name embeds the OCID, whose last label can start
     * with a digit, which {@link URI} rejects as a host, and Docker's default {@code bridge}
     * network has no name lookup at all.
     */
    String apiServerUrl(StoredOkeCluster cluster) throws IOException {
        if (!containerDetector.isRunningInContainer()) {
            return "https://127.0.0.1:" + cluster.getHostPort();
        }
        ContainerLifecycleManager.EndpointInfo endpoint;
        try {
            endpoint = lifecycleManager.resolveEndpoint(containerName(cluster), K3S_CONTAINER_PORT,
                    sidecarNetwork().orElse(null));
        } catch (RuntimeException e) {
            throw new IOException("Cannot inspect the k3s sidecar of cluster " + cluster.getId(), e);
        }
        String host = endpoint.host().contains(":") ? "[" + endpoint.host() + "]" : endpoint.host();
        return "https://" + host + ":" + endpoint.port();
    }

    /**
     * The network {@code ContainerBuilder.withDockerNetwork(Optional.empty())} attaches the sidecar
     * to: the configured {@code services.docker-network}, else floci-oci's own. A sidecar with a
     * published port also sits on {@code bridge}, which floci-oci cannot reach from its network.
     */
    private Optional<String> sidecarNetwork() {
        return config.services().dockerNetwork()
                .filter(n -> !n.isBlank())
                .or(networkResolver::resolveNetworkName);
    }

    /** The region the cluster was created in; clusters persisted before regions were recorded use the default. */
    private String clusterRegion(StoredOkeCluster cluster) {
        return cluster.getRegion() != null ? cluster.getRegion() : config.defaultRegion();
    }

    private String containerName(StoredOkeCluster cluster) {
        return ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId());
    }

    private static Optional<String> get(SSLContext sslContext, String url, String bearerToken)
            throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(PROBE_TIMEOUT)
                .build()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(PROBE_TIMEOUT).GET();
            if (bearerToken != null) {
                request.header("Authorization", "Bearer " + bearerToken);
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        }
    }

    private static SSLContext tlsContext(TrustManager trustManager) throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {trustManager}, null);
        return context;
    }

    /**
     * Bootstrap only: the CA this lets floci-oci fetch is what the next call is verified against.
     * Extends {@link X509ExtendedTrustManager} so the JDK does not add its own host name check.
     */
    private static final class TrustAnyCertificate extends X509ExtendedTrustManager {
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Accepted: see the class comment.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
            // Accepted: see the class comment.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            // Accepted: see the class comment.
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            throw new UnsupportedOperationException("floci-oci is the client here");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
            throw new UnsupportedOperationException("floci-oci is the client here");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            throw new UnsupportedOperationException("floci-oci is the client here");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /**
     * Verifies the server's certificate chain against the cluster's own CA, without matching the
     * host name: inside Docker floci-oci dials the sidecar's IP, which k3s's certificate does not
     * list because the shared network is attached after k3s starts.
     */
    private static final class TrustClusterCa extends X509ExtendedTrustManager {
        private final X509TrustManager delegate;

        TrustClusterCa(String caPem) throws GeneralSecurityException, IOException {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certificates =
                    factory.generateCertificates(new ByteArrayInputStream(caPem.getBytes(StandardCharsets.UTF_8)));
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int index = 0;
            for (Certificate certificate : certificates) {
                trustStore.setCertificateEntry("k3s-ca-" + index++, certificate);
            }
            TrustManagerFactory factoryForCa = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factoryForCa.init(trustStore);
            this.delegate = (X509TrustManager) factoryForCa.getTrustManagers()[0];
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            throw new UnsupportedOperationException("floci-oci is the client here");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
            throw new UnsupportedOperationException("floci-oci is the client here");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            throw new UnsupportedOperationException("floci-oci is the client here");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }

    public void registerExistingCluster(StoredOkeCluster cluster) {
        if (cluster == null || cluster.getId() == null || config.services().oke().mock()) {
            return;
        }
        startCluster(cluster);
    }

    public void stopCluster(StoredOkeCluster cluster) {
        ActiveClusterRef ref = activeClusters.get(cluster.getId());
        String containerName = ref != null ? ref.containerName() : containerName(cluster);
        String volumeName = ref != null ? ref.volumeName() : ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId());
        int hostPort = ref != null && ref.hostPort() > 0 ? ref.hostPort() : cluster.getHostPort();

        lifecycleManager.removeIfExists(containerName);

        try {
            removeVolumeIfConfigured(volumeName);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to remove volume '%s' for cluster '%s'", volumeName, cluster.getId());
        }

        if (hostPort > 0) {
            portAllocator.release(hostPort);
        }
        activeClusters.remove(cluster.getId());
        LOG.infof("Stopped k3s sidecar container '%s' and released host port %d", containerName, hostPort);
    }

    /** Stops every sidecar; volumes follow the delete policy, so persistent clusters come back. */
    @PreDestroy
    void shutdown() {
        removeAll(false);
    }

    /**
     * A state reset forgets every cluster, so every volume goes too, whatever
     * {@code prune-volumes-on-delete} says: nothing could reach them afterwards.
     */
    @Override
    public void clear() {
        removeAll(true);
    }

    private void removeAll(boolean dropVolumes) {
        for (Map.Entry<String, ActiveClusterRef> entry : activeClusters.entrySet()) {
            ActiveClusterRef ref = entry.getValue();
            try {
                lifecycleManager.removeIfExists(ref.containerName());
            } catch (Exception e) {
                LOG.warnf(e, "Failed to remove k3s sidecar container '%s'", ref.containerName());
            }
            try {
                if (dropVolumes) {
                    lifecycleManager.removeVolume(ref.volumeName());
                } else {
                    removeVolumeIfConfigured(ref.volumeName());
                }
            } catch (Exception e) {
                LOG.warnf(e, "Failed to remove k3s volume '%s'", ref.volumeName());
            }
            try {
                portAllocator.release(ref.hostPort());
                LOG.infof("Cleaned up k3s sidecar container '%s' and released host port %d", ref.containerName(), ref.hostPort());
            } catch (Exception e) {
                LOG.warnf(e, "Failed to release host port %d for container '%s'", ref.hostPort(), ref.containerName());
            }
        }
        activeClusters.clear();
    }

    /** Whether stopping a cluster, including at shutdown, removes its volume and so its k3s CA. */
    boolean prunesVolumesOnStop() {
        return "memory".equalsIgnoreCase(config.storage().mode()) || config.storage().pruneVolumesOnDelete();
    }

    private void removeVolumeIfConfigured(String volumeName) {
        if (prunesVolumesOnStop()) {
            lifecycleManager.removeVolume(volumeName);
        } else {
            LOG.infof("Retained Docker volume '%s'. Remove manually: docker volume rm %s", volumeName, volumeName);
        }
    }
}
