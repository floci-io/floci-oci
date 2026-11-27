package io.floci.oci.services.database;

import com.github.dockerjava.api.command.HealthState;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.core.common.docker.ImageCacheService;
import io.floci.oci.core.common.docker.PortAllocator;
import io.floci.oci.services.database.model.StoredAutonomousDatabase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Runs one Autonomous Database Free container per Autonomous Database
 * (local/oracle/oci-docs/adb-free-spike.md). It owns only the mechanics: it never reads the
 * {@code mock} flag (the service gates every call) and knows nothing about lifecycle states or
 * work requests.
 *
 * <p>A container is never recreated. The database lives in the container's own filesystem, and
 * a new container would start an empty database with an admin password floci-oci never keeps, so
 * Stop, Start and Restart map to {@code docker stop}, {@code start} and {@code restart} of the
 * same container, and only a delete removes it. That is also why the host port stays reserved
 * while a database is stopped: Docker binds the same port again when the container starts.
 */
@ApplicationScoped
public class AutonomousDatabaseContainerManager {

    private static final Logger LOG = Logger.getLogger(AutonomousDatabaseContainerManager.class);

    /** The container's mTLS listener; its 1521 TLS listener is not published (spike, row 4). */
    static final int MTLS_PORT = 1522;
    static final String WALLET_DIR = "/u01/app/oracle/wallets/tls_wallet";
    static final String WALLET_PASSWORD_ENV = "WALLET_PASSWORD";
    private static final int SHORT_ID_LENGTH = 16;
    private static final int STOP_TIMEOUT_SECONDS = 60;
    private static final int LOG_TAIL_BYTES = 64 * 1024;
    private static final Duration LOG_TIMEOUT = Duration.ofSeconds(5);
    private static final String WALLET_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    /** What Docker reports about a database's container. */
    public enum Health {
        /** The image's HEALTHCHECK passed: the instance runs and the listener serves every service. */
        HEALTHY,
        /** Running, with the health check not passed yet. */
        STARTING,
        /** Running, with the health check failing; it may still recover. */
        UNHEALTHY,
        /** The container exists but is not running. */
        EXITED,
        /** There is no such container. */
        MISSING
    }

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ImageCacheService imageCacheService;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;

    @Inject
    public AutonomousDatabaseContainerManager(ContainerBuilder containerBuilder,
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

    /** The container's name: the end of the OCID's unique part, since a name must fit a DNS label. */
    public String containerName(String autonomousDatabaseId) {
        return ContainerStorageHelper.dockerName(config, "adb-"
                + autonomousDatabaseId.substring(Math.max(0, autonomousDatabaseId.length() - SHORT_ID_LENGTH)));
    }

    /** The address clients are given for the database's mTLS listener. */
    public EndpointInfo clientEndpoint(StoredAutonomousDatabase database) {
        return lifecycleManager.clientEndpoint(database.getContainerName(), MTLS_PORT, database.getHostPort());
    }

    /**
     * Creates and starts the database's container and records its host port. The image is pulled
     * before a port is claimed, so a slow first pull (about 12 GB) holds nothing. The wallet
     * password is generated here and lives only in the container's environment. On failure the
     * container is removed and the port released before the exception is rethrown.
     */
    public void create(StoredAutonomousDatabase database, String adminPassword) {
        String image = config.services().database().image();
        imageCacheService.ensureImageExists(containerBuilder.newContainer(image).build().image());

        String name = database.getContainerName();
        int hostPort = claimPort(database.getHostPort());
        try {
            ContainerSpec spec = containerBuilder.newContainer(image)
                    .withName(name)
                    .withEnv("WORKLOAD_TYPE", workloadType(database))
                    .withEnv("ADMIN_PASSWORD", adminPassword)
                    .withEnv(WALLET_PASSWORD_ENV, walletPassword())
                    .withEnv("DATABASE_NAME", database.getDbName())
                    .withPortBinding(MTLS_PORT, hostPort)
                    .withCapAdd("SYS_ADMIN")
                    .withDevice("/dev/fuse")
                    .withDockerNetwork(Optional.empty())
                    .withEmbeddedDns()
                    .withLogRotation()
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels(AutonomousDatabaseService.SERVICE,
                            database.getId(), database.getCompartmentId(), database.getRegion()))
                    .build();
            lifecycleManager.createAndStart(spec);
            database.setHostPort(hostPort);
            LOG.infov("Started Autonomous Database Free for {0} on host port {1}", database.getId(), hostPort);
        } catch (RuntimeException e) {
            lifecycleManager.removeIfExists(name);
            portAllocator.release(hostPort);
            throw e;
        }
    }

    /** The image knows two workloads: ADW for a data warehouse, ATP for everything else. */
    static String workloadType(StoredAutonomousDatabase database) {
        return "DW".equals(database.getAttributes().get("dbWorkload")) ? "ADW" : "ATP";
    }

    /** Letters and digits, with at least one of each, which the image's 8-character minimum accepts. */
    static String walletPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder("Fw");
        for (int i = 0; i < 22; i++) {
            password.append(WALLET_ALPHABET.charAt(random.nextInt(WALLET_ALPHABET.length())));
        }
        return password.append('7').toString();
    }

    /**
     * Takes over the container a previous run of floci-oci left, found by its name and confirmed
     * by its resource label, and reserves its host port again.
     *
     * @return false when the container is gone, and with it the database
     */
    public boolean adopt(StoredAutonomousDatabase database) {
        Optional<Container> found = lifecycleManager.findByName(database.getContainerName())
                .filter(container -> container.getLabels() != null && database.getId()
                        .equals(container.getLabels().get(ContainerStorageHelper.RESOURCE_ID_LABEL)));
        if (found.isEmpty()) {
            return false;
        }
        if (database.getHostPort() > 0) {
            portAllocator.markReserved(database.getHostPort());
        }
        LOG.infov("Adopted the container of Autonomous Database {0}", database.getId());
        return true;
    }

    /** Starts the stopped container; a running one is left alone. */
    public void start(StoredAutonomousDatabase database) {
        InspectContainerResponse inspect = lifecycleManager.inspect(database.getContainerName())
                .orElseThrow(() -> gone(database));
        if (!Boolean.TRUE.equals(inspect.getState().getRunning())) {
            lifecycleManager.start(database.getContainerName());
        }
    }

    /** Stops the container and keeps it, with its data and its host port. */
    public void stop(StoredAutonomousDatabase database) {
        try {
            lifecycleManager.stop(database.getContainerName(), STOP_TIMEOUT_SECONDS);
        } catch (NotFoundException e) {
            throw gone(database);
        }
    }

    /** Stops the container as floci-oci shuts down, waiting at most {@code timeoutSeconds}. */
    public void stopForShutdown(StoredAutonomousDatabase database, int timeoutSeconds) {
        try {
            lifecycleManager.stop(database.getContainerName(), timeoutSeconds);
        } catch (NotFoundException e) {
            LOG.debugv("The container of Autonomous Database {0} is already gone", database.getId());
        }
    }

    public void restart(StoredAutonomousDatabase database) {
        try {
            lifecycleManager.restart(database.getContainerName(), STOP_TIMEOUT_SECONDS);
        } catch (NotFoundException e) {
            throw gone(database);
        }
    }

    /** Removes the container, and the database with it, and releases its port. */
    public void remove(StoredAutonomousDatabase database) {
        lifecycleManager.removeIfExists(database.getContainerName());
        if (database.getHostPort() > 0) {
            portAllocator.release(database.getHostPort());
        }
    }

    /** The container's state and the result of the image's health check. */
    public Health health(StoredAutonomousDatabase database) {
        Optional<InspectContainerResponse> inspect = lifecycleManager.inspect(database.getContainerName());
        if (inspect.isEmpty()) {
            return Health.MISSING;
        }
        InspectContainerResponse.ContainerState state = inspect.get().getState();
        if (state == null || !Boolean.TRUE.equals(state.getRunning())) {
            return Health.EXITED;
        }
        HealthState health = state.getHealth();
        String status = health != null ? health.getStatus() : null;
        if ("healthy".equals(status)) {
            return Health.HEALTHY;
        }
        return "unhealthy".equals(status) ? Health.UNHEALTHY : Health.STARTING;
    }

    /** The end of the container's log, which says why a first start failed. Empty when there is none. */
    public String logTail(StoredAutonomousDatabase database, int maxChars) {
        String logs = lifecycleManager.logs(database.getContainerName(), LOG_TAIL_BYTES, LOG_TIMEOUT).strip();
        return logs.length() <= maxChars ? logs : logs.substring(logs.length() - maxChars);
    }

    /** The container's own {@code tnsnames.ora}, which names the database's aliases. */
    public String tnsnames(StoredAutonomousDatabase database) {
        byte[] file = walletFiles(database).get("tnsnames.ora");
        if (file == null) {
            throw new IllegalStateException("The wallet of Autonomous Database " + database.getId()
                    + " has no tnsnames.ora");
        }
        return new String(file, StandardCharsets.UTF_8);
    }

    /**
     * GenerateAutonomousDatabaseWallet: the container's {@code tls_wallet}, its keystores
     * re-encrypted from the container's wallet password to {@code password}, with
     * {@code tnsnames.ora} naming the address clients reach the database at.
     */
    public byte[] wallet(StoredAutonomousDatabase database, String password) {
        Map<String, byte[]> files = walletFiles(database);
        String containerPassword = lifecycleManager.containerEnv(database.getContainerName()).stream()
                .filter(entry -> entry.startsWith(WALLET_PASSWORD_ENV + "="))
                .map(entry -> entry.substring(WALLET_PASSWORD_ENV.length() + 1))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The container of Autonomous Database "
                        + database.getId() + " has no " + WALLET_PASSWORD_ENV));
        EndpointInfo endpoint = clientEndpoint(database);
        return AutonomousDatabaseWallet.zip(files, containerPassword.toCharArray(), password.toCharArray(),
                endpoint.host(), endpoint.port());
    }

    /** Every file of the container's {@code tls_wallet} directory, by file name. */
    private Map<String, byte[]> walletFiles(StoredAutonomousDatabase database) {
        Path archive = null;
        try {
            Path directory = Files.createTempDirectory("floci-oci-adb-wallet");
            archive = directory.resolve("tls_wallet.tar");
            lifecycleManager.copyFromContainer(database.getContainerName(), WALLET_DIR, archive);
            try (InputStream in = Files.newInputStream(archive)) {
                return untar(in);
            }
        } catch (NotFoundException e) {
            throw gone(database);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the wallet of Autonomous Database "
                    + database.getId(), e);
        } finally {
            deleteQuietly(archive);
        }
    }

    /** The regular files of a tar archive by file name, wherever they sit in it. */
    static Map<String, byte[]> untar(InputStream in) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                if (entry.isFile()) {
                    String name = entry.getName();
                    files.put(name.substring(name.lastIndexOf('/') + 1), tar.readAllBytes());
                }
            }
        }
        return files;
    }

    private static void deleteQuietly(Path archive) {
        if (archive == null) {
            return;
        }
        try {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(archive.getParent());
        } catch (IOException e) {
            LOG.debugv("Could not remove {0}: {1}", archive, e.getMessage());
        }
    }

    private static IllegalStateException gone(StoredAutonomousDatabase database) {
        LOG.debugv("The container of Autonomous Database {0} is gone", database.getId());
        return new IllegalStateException(AutonomousDatabaseService.CONTAINER_GONE);
    }

    /**
     * The port the database had before, when nothing else holds it, so its endpoint survives;
     * otherwise a free one from the configured range.
     */
    private int claimPort(int previous) {
        if (previous > 0 && portAllocator.reclaim(previous)) {
            return previous;
        }
        return portAllocator.allocate(config.services().database().basePort(),
                config.services().database().maxPort());
    }
}
