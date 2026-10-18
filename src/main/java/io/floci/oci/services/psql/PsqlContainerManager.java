package io.floci.oci.services.psql;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.ExecResult;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.core.common.docker.ImageCacheService;
import io.floci.oci.core.common.docker.PortAllocator;
import io.floci.oci.services.psql.model.StoredDbSystem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Runs one PostgreSQL container per DB system. It owns only the mechanics: it never reads the
 * {@code mock} flag (the service gates every call) and knows nothing about lifecycle states or
 * work requests.
 */
@ApplicationScoped
public class PsqlContainerManager {

    private static final Logger LOG = Logger.getLogger(PsqlContainerManager.class);

    static final int POSTGRES_PORT = 5432;
    private static final int SHORT_ID_LENGTH = 16;
    private static final String TLS_DIR = "/etc/floci-tls";
    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Writes the certificate and key the container received in its environment where Postgres
     * can read them, then hands over to the image's own entrypoint with TLS switched on.
     */
    private static final String ENTRYPOINT = String.join("\n",
            "set -e",
            "mkdir -p " + TLS_DIR,
            "printf '%s\\n' \"$FLOCI_TLS_CERT\" > " + TLS_DIR + "/server.crt",
            "printf '%s\\n' \"$FLOCI_TLS_KEY\" > " + TLS_DIR + "/server.key",
            "chmod 600 " + TLS_DIR + "/server.key",
            "chown postgres:postgres " + TLS_DIR + "/server.crt " + TLS_DIR + "/server.key",
            "unset FLOCI_TLS_CERT FLOCI_TLS_KEY",
            "exec docker-entrypoint.sh postgres -c ssl=on"
                    + " -c ssl_cert_file=" + TLS_DIR + "/server.crt"
                    + " -c ssl_key_file=" + TLS_DIR + "/server.key");

    /** Reads the statement from stdin, where psql substitutes its variables; {@code -c} does not. */
    private static final String RESET_PASSWORD = "printf '%s\\n' 'ALTER ROLE :\"role\" WITH PASSWORD :'\"'\"'password'\"'\"';'"
            + " | psql -v ON_ERROR_STOP=1 -v role=\"$FLOCI_ROLE\" -v password=\"$FLOCI_PASSWORD\""
            + " -U \"$FLOCI_ROLE\" -d postgres";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ImageCacheService imageCacheService;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;

    @Inject
    public PsqlContainerManager(ContainerBuilder containerBuilder,
                                ContainerLifecycleManager lifecycleManager,
                                ImageCacheService imageCacheService,
                                PortAllocator portAllocator,
                                EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.imageCacheService = imageCacheService;
        this.portAllocator = portAllocator;
        this.config = config;
    }

    /**
     * The container's name, which is also the host name other containers reach it by and the
     * subject of its certificate, so it has to fit a DNS label: the end of the OCID's unique
     * part stands in for the whole OCID, which the container's labels carry in full.
     */
    public String containerName(String dbSystemId) {
        return ContainerStorageHelper.dockerName(config, "psql-" + shortId(dbSystemId));
    }

    public String volumeName(String dbSystemId) {
        return ContainerStorageHelper.dockerName(config, "psql-vol-" + shortId(dbSystemId));
    }

    private static String shortId(String dbSystemId) {
        return dbSystemId.substring(Math.max(0, dbSystemId.length() - SHORT_ID_LENGTH));
    }

    /** The address clients are given for this DB system's PostgreSQL port. */
    public EndpointInfo clientEndpoint(StoredDbSystem system) {
        return lifecycleManager.clientEndpoint(system.getContainerName(), POSTGRES_PORT, system.getHostPort());
    }

    /**
     * Starts the DB system's container and records its host port on {@code system}. The image
     * is pulled before a port is claimed, so a slow first pull holds nothing. On failure the
     * container is removed and the port released before the exception is rethrown.
     *
     * @param masterPassword the password for a first boot; ignored by Postgres when the data
     *                       volume is already initialised
     */
    public void start(StoredDbSystem system, String masterPassword) {
        String major = PsqlCatalog.major(system.getDbVersion());
        String image = config.services().psql().image().replace("{major}", major);
        imageCacheService.ensureImageExists(containerBuilder.newContainer(image).build().image());

        String name = system.getContainerName();
        lifecycleManager.removeIfExists(name);
        int hostPort = claimPort(system.getHostPort());
        try {
            lifecycleManager.ensureVolume(system.getVolumeName());
            ContainerSpec spec = containerBuilder.newContainer(image)
                    .withName(name)
                    .withEnv("POSTGRES_USER", system.getAdminUsername())
                    .withEnv("POSTGRES_PASSWORD", masterPassword)
                    .withEnv("POSTGRES_DB", "postgres")
                    .withEnv("FLOCI_TLS_CERT", system.getCertificatePem())
                    .withEnv("FLOCI_TLS_KEY", system.getPrivateKeyPem())
                    .withEntrypoint(List.of("sh", "-c", ENTRYPOINT))
                    .withPortBinding(POSTGRES_PORT, hostPort)
                    .withNamedVolume(system.getVolumeName(), dataPath(major))
                    .withDockerNetwork(Optional.empty())
                    .withLogRotation()
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                            "psql", system.getId(), system.getCompartmentId(), system.getRegion()))
                    .build();
            lifecycleManager.createAndStart(spec);
            system.setHostPort(hostPort);
            LOG.infov("Started PostgreSQL {0} for DB system {1} on host port {2}", major, system.getId(), hostPort);
        } catch (RuntimeException e) {
            lifecycleManager.removeIfExists(name);
            portAllocator.release(hostPort);
            throw e;
        }
    }

    public boolean isRunning(StoredDbSystem system) {
        return lifecycleManager.isContainerRunning(system.getContainerName());
    }

    /**
     * Whether Postgres accepts connections. Probes over TCP loopback on purpose: on a first
     * boot the image runs a temporary server that only listens on the Unix socket.
     */
    public boolean probeReady(StoredDbSystem system) {
        return lifecycleManager.exec(system.getContainerName(), List.of(),
                List.of("pg_isready", "-h", "127.0.0.1", "-p", String.valueOf(POSTGRES_PORT),
                        "-U", system.getAdminUsername(), "-d", "postgres"),
                PROBE_TIMEOUT).succeeded();
    }

    /** The server's own version, such as {@code 15.8}, or empty when it cannot be read. */
    public Optional<String> serverVersion(StoredDbSystem system) {
        ExecResult result = lifecycleManager.exec(system.getContainerName(), List.of(),
                List.of("psql", "-U", system.getAdminUsername(), "-d", "postgres", "-Atc", "SHOW server_version"),
                EXEC_TIMEOUT);
        if (!result.succeeded() || result.stdout().isBlank()) {
            return Optional.empty();
        }
        // "15.8 (Debian 15.8-1.pgdg120+1)" on some images: keep the version itself.
        return Optional.of(result.stdout().strip().split("\\s+")[0]);
    }

    /**
     * Changes the admin role's password over the container's local socket, which needs no
     * current password. The new password travels as an environment variable and a psql
     * variable, never as SQL text.
     *
     * @throws IllegalStateException when Postgres rejects the change; the message carries no password
     */
    public void resetPassword(StoredDbSystem system, String newPassword) {
        ExecResult result = lifecycleManager.exec(system.getContainerName(),
                List.of("FLOCI_ROLE=" + system.getAdminUsername(), "FLOCI_PASSWORD=" + newPassword,
                        "PGOPTIONS=-c statement_timeout=30000"),
                List.of("sh", "-c", RESET_PASSWORD), EXEC_TIMEOUT);
        if (!result.succeeded()) {
            throw new IllegalStateException("PostgreSQL rejected the password change (exit "
                    + result.exitCode() + "): " + result.stderr().replace(newPassword, "***").strip());
        }
    }

    /** Removes the container and releases its port. The data volume stays. */
    public void stop(StoredDbSystem system) {
        lifecycleManager.removeIfExists(system.getContainerName());
        if (system.getHostPort() > 0) {
            portAllocator.release(system.getHostPort());
        }
    }

    /**
     * Stops the container and removes its data volume, under the same policy as the other
     * sidecars: in {@code memory} storage mode, or when {@code prune-volumes-on-delete} is set.
     */
    public void remove(StoredDbSystem system) {
        stop(system);
        if ("memory".equalsIgnoreCase(config.storage().mode()) || config.storage().pruneVolumesOnDelete()) {
            lifecycleManager.removeVolume(system.getVolumeName());
        }
    }

    /** Stops the container and always removes its data volume: for a state reset. */
    public void purge(StoredDbSystem system) {
        stop(system);
        lifecycleManager.removeVolume(system.getVolumeName());
    }

    /** The host a client reaches a published port at, which the certificate has to name. */
    public String daemonHost() {
        return lifecycleManager.daemonHost();
    }

    /** The port the system had before, when it is still free, so its endpoint survives a restart. */
    private int claimPort(int previous) {
        if (previous > 0 && portAllocator.isPortFree(previous)) {
            portAllocator.markReserved(previous);
            return previous;
        }
        return portAllocator.allocate(config.services().psql().basePort(), config.services().psql().maxPort());
    }

    /** The official image moved its data directory in version 18. */
    static String dataPath(String major) {
        return Integer.parseInt(major) >= 18 ? "/var/lib/postgresql" : "/var/lib/postgresql/data";
    }
}
