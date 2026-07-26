package io.floci.oci.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "floci-oci")
public interface EmulatorConfig {

    @WithDefault("4599")
    int port();

    @WithDefault("http://localhost:4599")
    String baseUrl();

    /**
     * When set, overrides the hostname in base-url for URLs returned in API responses.
     * This is needed in multi-container Docker setups where "localhost" in the response
     * URL would resolve to the wrong container.
     *
     * Example: FLOCI_OCI_HOSTNAME=floci-oci
     */
    Optional<String> hostname();

    /**
     * Returns the effective base URL, taking hostname and TLS into account.
     * If hostname is set, replaces the host in baseUrl with it.
     * If TLS is enabled, switches the scheme from http:// to https://.
     */
    default String effectiveBaseUrl() {
        String url = hostname()
                .map(h -> baseUrl().replaceFirst("://[^:/]+(:\\d+)?", "://" + h + "$1"))
                .orElse(baseUrl());
        if (tls().enabled() && url.startsWith("http://")) {
            url = "https://" + url.substring(7);
        }
        return url;
    }

    @WithDefault("us-ashburn-1")
    String defaultRegion();

    /** OCI realm key used when minting OCIDs (oc1 = the commercial realm). */
    @WithDefault("oc1")
    String defaultRealm();

    /** Tenancy OCID used when a request carries no parseable Authorization signature. */
    @WithDefault("ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000")
    String defaultTenancyId();

    /**
     * The Object Storage namespace for the emulated tenancy. Real tenancies get an opaque
     * assigned namespace; the emulator uses a deterministic, configurable one and accepts
     * any namespace on read.
     */
    @WithDefault("floci-local")
    String defaultNamespace();

    @WithDefault("2048")
    int maxRequestSize();

    StorageConfig storage();

    DnsConfig dns();

    AuthConfig auth();

    SecurityConfig security();

    ServicesConfig services();

    DockerConfig docker();

    InitHooksConfig initHooks();

    TlsConfig tls();

    interface DnsConfig {
        /**
         * Additional hostname suffixes the embedded DNS server will resolve to the emulator's
         * container IP, alongside the primary {@code floci-oci.hostname}.
         *
         * Via environment variable (comma-separated for multiple values):
         * FLOCI_OCI_DNS_EXTRA_SUFFIXES=objectstorage.example.internal
         */
        Optional<List<String>> extraSuffixes();

        /**
         * When {@code true} (default), the configured {@link #containerFallbackServers()} are
         * appended after the embedded DNS to every spawned container's {@code HostConfig.Dns},
         * so public hostnames still resolve if the embedded forwarder cannot answer.
         */
        @WithDefault("true")
        boolean containerFallbackEnabled();

        /**
         * Ordered list of public DNS resolvers used both as the fallback upstream for the
         * embedded DNS forwarder and (when {@link #containerFallbackEnabled()}) as the
         * secondary resolvers injected into spawned containers.
         */
        @WithDefault("8.8.8.8,8.8.4.4")
        List<String> containerFallbackServers();
    }

    interface SecurityConfig {
        Optional<List<String>> extraCorsAllowedOrigins();

        Optional<List<String>> extraCorsAllowedHeaders();

        Optional<List<String>> extraCorsExposeHeaders();

        @WithDefault("false")
        boolean disableCorsHeaders();

        /**
         * Whether to grant Private Network Access preflights (respond with
         * {@code Access-Control-Allow-Private-Network: true}) when the browser asks.
         * Off by default: it lets a public origin reach the private network, so it
         * must be opted into explicitly.
         */
        @WithDefault("false")
        boolean corsAllowPrivateNetwork();
    }

    interface StorageConfig {
        /** Supported modes: memory, persistent, hybrid, wal. */
        @WithDefault("memory")
        String mode();

        @WithDefault("./data")
        String persistentPath();

        /** The path on the host machine where data is stored. Useful for Docker-in-Docker. */
        @WithDefault("${floci-oci.storage.persistent-path}")
        String hostPersistentPath();

        /**
         * When {@code true}, named volumes are removed immediately after a child container stops
         * on resource delete. In {@code memory} storage mode volumes are always removed regardless
         * of this flag. Defaults to {@code false} to match real OCI behaviour (data survives delete).
         */
        @WithDefault("false")
        boolean pruneVolumesOnDelete();

        WalConfig wal();

        /**
         * Per-service storage overrides keyed by storage key, e.g.
         * {@code floci-oci.storage.services.objectstorage.mode=wal}. Services without an
         * entry inherit the global {@link #mode()} and the default flush interval.
         */
        Map<String, ServiceStorageConfig> services();
    }

    interface ServiceStorageConfig {
        Optional<String> mode();

        @WithDefault("5000")
        long flushIntervalMs();
    }

    interface WalConfig {
        @WithDefault("30000")
        long compactionIntervalMs();
    }

    interface AuthConfig {
        /**
         * When {@code true}, requests without a structurally valid OCI Signature
         * {@code Authorization} header are rejected with 401 NotAuthenticated.
         * The RSA signature itself is never verified — only parsed for tenancy/user context.
         */
        @WithDefault("false")
        boolean requireSignature();
    }

    interface ServicesConfig {
        /** Optional shared Docker network for sidecar containers. */
        Optional<String> dockerNetwork();

        IdentityServiceConfig identity();

        ObjectStorageServiceConfig objectstorage();

        QueueServiceConfig queue();

        KmsServiceConfig kms();

        VaultServiceConfig vault();

        StreamingServiceConfig streaming();

        FunctionsServiceConfig functions();

        interface IdentityServiceConfig {
            @WithDefault("true")
            boolean enabled();
        }

        interface ObjectStorageServiceConfig {
            @WithDefault("true")
            boolean enabled();
        }

        interface QueueServiceConfig {
            @WithDefault("true")
            boolean enabled();
        }

        interface KmsServiceConfig {
            @WithDefault("true")
            boolean enabled();
        }

        interface VaultServiceConfig {
            @WithDefault("true")
            boolean enabled();
        }

        interface StreamingServiceConfig {
            @WithDefault("true")
            boolean enabled();
        }

        interface FunctionsServiceConfig {
            @WithDefault("true")
            boolean enabled();

            /**
             * When {@code true}, no Docker containers are started: the management plane
             * works fully and invocations return a synthetic body. The only container
             * toggle for this service. Env: FLOCI_OCI_SERVICES_FUNCTIONS_MOCK
             */
            @WithDefault("false")
            boolean mock();

            /** The Fn Project server image backing real invocations. */
            @WithDefault("fnproject/fnserver:latest")
            String serverImage();

            @WithDefault("8085")
            int serverBasePort();

            @WithDefault("8095")
            int serverMaxPort();

            @WithDefault("120")
            int startupTimeoutSeconds();
        }
    }

    interface InitHooksConfig {
        @WithDefault("/bin/sh")
        String shellExecutable();

        @WithDefault("2")
        long shutdownGracePeriodSeconds();

        @WithDefault("30")
        long timeoutSeconds();
    }

    /**
     * Optional TLS configuration for enabling HTTPS on the emulator.
     * Both HTTP and HTTPS are served simultaneously via protocol sniffing.
     */
    interface TlsConfig {
        /** Enable TLS/HTTPS on the server. Env: FLOCI_OCI_TLS_ENABLED */
        @WithDefault("false")
        boolean enabled();

        /** Path to PEM certificate file. Env: FLOCI_OCI_TLS_CERT_PATH */
        Optional<String> certPath();

        /** Path to PEM private key file. Env: FLOCI_OCI_TLS_KEY_PATH */
        Optional<String> keyPath();

        /**
         * Auto-generate a self-signed certificate when no cert-path/key-path provided.
         * The generated files are persisted to {@code {storage.persistent-path}/tls/}
         * and reused across restarts. Env: FLOCI_OCI_TLS_SELF_SIGNED
         */
        @WithDefault("true")
        boolean selfSigned();

        /**
         * Additional port the TLS proxy binds for clients that assume OCI lives on 443,
         * alongside the public {@link EmulatorConfig#port()}. Set to {@code 0} to disable
         * the extra binding. Env: FLOCI_OCI_TLS_HTTPS_PORT
         */
        @WithDefault("443")
        int httpsPort();
    }

    /**
     * Configuration for Docker container management shared across all services
     * that spawn Docker containers.
     */
    interface DockerConfig {
        /** Maximum size of each container log file before rotation (json-file max-size format). */
        @WithDefault("10m")
        String logMaxSize();

        /** Maximum number of rotated log files to retain per container. */
        @WithDefault("3")
        String logMaxFile();

        /** Unix socket or TCP URL for the Docker daemon (e.g. unix:///var/run/docker.sock). */
        @WithDefault("unix:///var/run/docker.sock")
        String dockerHost();

        /**
         * Optional override for the address at which spawned containers reach the emulator.
         * When empty it is auto-detected (container IP inside Docker, host.docker.internal
         * on the host).
         */
        Optional<String> hostOverride();

        /**
         * Optional namespace inserted into emulator-managed child container and volume names.
         * Useful when multiple emulator processes share one Docker daemon.
         */
        Optional<String> resourceNamespace();

        /**
         * Optional registry/repository base for every Docker image the emulator launches.
         */
        Optional<String> imageRegistryBase();

        /**
         * Path to a directory containing Docker's config.json (e.g. /root/.docker).
         */
        Optional<String> dockerConfigPath();

        /**
         * Explicit credentials for private Docker registries.
         */
        @WithDefault("")
        List<RegistryCredential> registryCredentials();

        interface RegistryCredential {
            /** Registry hostname (e.g. myregistry.example.com). */
            String server();

            String username();

            String password();
        }
    }
}
