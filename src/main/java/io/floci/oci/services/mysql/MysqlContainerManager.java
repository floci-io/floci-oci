package io.floci.oci.services.mysql;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.core.common.docker.ImageCacheService;
import io.floci.oci.core.common.docker.PortAllocator;
import io.floci.oci.services.mysql.model.StoredMysqlDbSystem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs one MySQL container per DB system. It owns only the mechanics: it never reads the
 * {@code mock} flag (the service gates every call) and knows nothing about lifecycle states or
 * work requests.
 */
@ApplicationScoped
public class MysqlContainerManager {

    private static final Logger LOG = Logger.getLogger(MysqlContainerManager.class);

    static final int MYSQL_PORT = 3306;
    static final String ROOT = "root";
    private static final int SHORT_ID_LENGTH = 16;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final String INIT_FILE = "/docker-entrypoint-initdb.d/floci-admin.sql";

    /**
     * Writes the statement that creates the admin user, received in the environment, where the
     * image runs it on the first initialisation of the data directory, then hands over to the
     * image's own entrypoint. The image's {@code MYSQL_USER} cannot be used: it gets no
     * privileges without a database of its own, while the admin of a DB system has them all.
     */
    static final String ENTRYPOINT = String.join("\n",
            "set -e",
            "if [ -n \"$FLOCI_INIT_SQL\" ]; then printf '%s\\n' \"$FLOCI_INIT_SQL\" > " + INIT_FILE + "; fi",
            "unset FLOCI_INIT_SQL",
            "exec docker-entrypoint.sh mysqld");

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ImageCacheService imageCacheService;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;

    @Inject
    public MysqlContainerManager(ContainerBuilder containerBuilder,
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
    public String containerName(String dbSystemId) {
        return ContainerStorageHelper.dockerName(config, "mysql-" + shortId(dbSystemId));
    }

    public String volumeName(String dbSystemId) {
        return ContainerStorageHelper.dockerName(config, "mysql-vol-" + shortId(dbSystemId));
    }

    private static String shortId(String dbSystemId) {
        return dbSystemId.substring(Math.max(0, dbSystemId.length() - SHORT_ID_LENGTH));
    }

    /** The address clients are given for this DB system's MySQL port. */
    public EndpointInfo clientEndpoint(StoredMysqlDbSystem system) {
        return lifecycleManager.clientEndpoint(system.getContainerName(), MYSQL_PORT, system.getHostPort());
    }

    /**
     * Starts the DB system's container and records its host port on {@code system}. The image is
     * pulled before a port is claimed, so a slow first pull holds nothing. On failure the
     * container is removed and the port released before the exception is rethrown.
     *
     * @param adminPassword the admin's password for a first boot; null when the data volume is
     *                      expected to be initialised already, in which case MySQL ignores it anyway
     */
    public void start(StoredMysqlDbSystem system, String adminPassword) {
        String image = image(system.getMysqlVersion());
        imageCacheService.ensureImageExists(containerBuilder.newContainer(image).build().image());

        String name = system.getContainerName();
        lifecycleManager.removeIfExists(name);
        int hostPort = claimPort(system.getHostPort());
        try {
            lifecycleManager.ensureVolume(system.getVolumeName());
            boolean adminIsRoot = ROOT.equals(system.getAdminUsername());
            String rootPassword = adminIsRoot && adminPassword != null ? adminPassword : UUID.randomUUID().toString();
            String initSql = adminPassword != null && !adminIsRoot
                    ? createAdminSql(system.getAdminUsername(), adminPassword) : "";
            ContainerSpec spec = containerBuilder.newContainer(image)
                    .withName(name)
                    .withEnv("MYSQL_ROOT_PASSWORD", rootPassword)
                    .withEnv("FLOCI_INIT_SQL", initSql)
                    .withEntrypoint(List.of("sh", "-c", ENTRYPOINT))
                    .withPortBinding(MYSQL_PORT, hostPort)
                    .withNamedVolume(system.getVolumeName(), "/var/lib/mysql")
                    .withDockerNetwork(Optional.empty())
                    .withLogRotation()
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                            "mysql", system.getId(), system.getCompartmentId(), system.getRegion()))
                    .build();
            lifecycleManager.createAndStart(spec);
            system.setHostPort(hostPort);
            LOG.infov("Started MySQL {0} for DB system {1} on host port {2}", system.getMysqlVersion(),
                    system.getId(), hostPort);
        } catch (RuntimeException e) {
            lifecycleManager.removeIfExists(name);
            portAllocator.release(hostPort);
            throw e;
        }
    }

    String image(String mysqlVersion) {
        return config.services().mysql().image().replace("{version}", mysqlVersion);
    }

    /**
     * The statement that creates the admin with every privilege. Both values are SQL string
     * literals, so backslashes and quotes are escaped; nothing else in a literal is special.
     */
    static String createAdminSql(String username, String password) {
        String user = "'" + literal(username) + "'@'%'";
        return "CREATE USER " + user + " IDENTIFIED BY '" + literal(password) + "';\n"
                + "GRANT ALL PRIVILEGES ON *.* TO " + user + " WITH GRANT OPTION;";
    }

    private static String literal(String value) {
        return value.replace("\\", "\\\\").replace("'", "''");
    }

    public boolean isRunning(StoredMysqlDbSystem system) {
        return lifecycleManager.isContainerRunning(system.getContainerName());
    }

    /**
     * Whether MySQL accepts connections. Probes over TCP on purpose: during a first boot the image
     * runs a temporary server without networking while the init scripts run.
     */
    public boolean probeReady(StoredMysqlDbSystem system) {
        return lifecycleManager.exec(system.getContainerName(), List.of(),
                List.of("mysqladmin", "ping", "-h", "127.0.0.1", "-P", String.valueOf(MYSQL_PORT), "--silent"),
                PROBE_TIMEOUT).succeeded();
    }

    /** Removes the container and releases its port. The data volume stays. */
    public void stop(StoredMysqlDbSystem system) {
        lifecycleManager.removeIfExists(system.getContainerName());
        if (system.getHostPort() > 0) {
            portAllocator.release(system.getHostPort());
        }
    }

    /**
     * Stops the container and removes its data volume, under the same policy as the other
     * sidecars: in {@code memory} storage mode, or when {@code prune-volumes-on-delete} is set.
     */
    public void remove(StoredMysqlDbSystem system) {
        stop(system);
        if ("memory".equalsIgnoreCase(config.storage().mode()) || config.storage().pruneVolumesOnDelete()) {
            lifecycleManager.removeVolume(system.getVolumeName());
        }
    }

    /** Stops the container and always removes its data volume: for a state reset. */
    public void purge(StoredMysqlDbSystem system) {
        stop(system);
        lifecycleManager.removeVolume(system.getVolumeName());
    }

    /** The port the system had before, when it is still free, so its endpoint survives a restart. */
    private int claimPort(int previous) {
        if (previous > 0 && portAllocator.isPortFree(previous)) {
            portAllocator.markReserved(previous);
            return previous;
        }
        return portAllocator.allocate(config.services().mysql().basePort(), config.services().mysql().maxPort());
    }
}
