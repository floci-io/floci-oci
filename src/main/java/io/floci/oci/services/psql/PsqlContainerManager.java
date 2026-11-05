package io.floci.oci.services.psql;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.ServiceConfigAccess;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.ExecResult;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.core.common.docker.ImageCacheService;
import io.floci.oci.core.common.docker.PortAllocator;
import io.floci.oci.services.psql.model.StoredDbSystem;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

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
    private static final Duration DUMP_TIMEOUT = Duration.ofMinutes(10);
    /**
     * Where a dump sits inside a container, only while it is written or loaded. Dumps are kept
     * outside the containers, because the admin of a DB system is a superuser who can read any
     * file the container can see.
     */
    private static final String DUMP_DIR = "/tmp";
    private static final String DUMPS_STORE = "psql-backups";
    /**
     * Any error stops the load and fails the restore, so a partial restore never looks like a
     * good one. The one statement that may fail is taken out first: {@code CREATE ROLE} for a role
     * the system already has, such as its admin. Roles come before the first {@code \connect} of
     * a {@code pg_dumpall}, so that is as far as the filter looks, and table data stays untouched.
     */
    private static final String RESTORE = String.join("\n",
            "set -e",
            "test -f \"$FLOCI_DUMP\"",
            "printf '%s\\n' \"SELECT format('DROP DATABASE %I WITH (FORCE)', datname) FROM pg_database"
                    + " WHERE NOT datistemplate \\\\gexec\" 'CREATE DATABASE postgres;'"
                    + " | psql -v ON_ERROR_STOP=1 -q -U \"$FLOCI_ROLE\" -d template1",
            "psql -Atq -U \"$FLOCI_ROLE\" -d template1"
                    + " -c \"SELECT format('CREATE ROLE %I;', rolname) FROM pg_roles\" > \"$FLOCI_DUMP.roles\"",
            "awk 'NR == FNR { existing[$0]; next } /^\\\\connect / { rolesDone = 1 }"
                    + " rolesDone || !($0 in existing)' \"$FLOCI_DUMP.roles\" \"$FLOCI_DUMP\" > \"$FLOCI_DUMP.load\"",
            "psql -v ON_ERROR_STOP=1 -q -U \"$FLOCI_ROLE\" -d template1 -f \"$FLOCI_DUMP.load\" > /dev/null");

    /**
     * Writes the certificate and key the container received in its environment where Postgres
     * can read them, then hands over to the image's own entrypoint with TLS switched on. The
     * script's own arguments ({@code "$@"}) are further {@code -c key=value} settings: passing
     * them as arguments, not text, needs no shell quoting.
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
                    + " -c ssl_key_file=" + TLS_DIR + "/server.key \"$@\"");

    /** Reads the statement from stdin, where psql substitutes its variables; {@code -c} does not. */
    private static final String RESET_PASSWORD = "printf '%s\\n' 'ALTER ROLE :\"role\" WITH PASSWORD :'\"'\"'password'\"'\"';'"
            + " | psql -v ON_ERROR_STOP=1 -v role=\"$FLOCI_ROLE\" -v password=\"$FLOCI_PASSWORD\""
            + " -U \"$FLOCI_ROLE\" -d postgres";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ImageCacheService imageCacheService;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;
    private final ServiceConfigAccess serviceConfigAccess;

    @Inject
    public PsqlContainerManager(ContainerBuilder containerBuilder,
                                ContainerLifecycleManager lifecycleManager,
                                ImageCacheService imageCacheService,
                                PortAllocator portAllocator,
                                EmulatorConfig config,
                                ServiceConfigAccess serviceConfigAccess) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.imageCacheService = imageCacheService;
        this.portAllocator = portAllocator;
        this.config = config;
        this.serviceConfigAccess = serviceConfigAccess;
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
                    .withEntrypoint(entrypoint(system))
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

    /**
     * The container's command: the TLS script, then a {@code -c} for each overridden setting
     * that needs a restart. The others are applied by {@link #applySettings} once the server is
     * up, since a setting given on the command line cannot be changed by a reload.
     */
    static List<String> entrypoint(StoredDbSystem system) {
        List<String> command = new ArrayList<>(List.of("sh", "-c", ENTRYPOINT, "sh"));
        Map<String, String> overrides = system.getConfigOverrides() != null ? system.getConfigOverrides() : Map.of();
        for (PsqlCatalog.Parameter parameter : PsqlCatalog.PARAMETERS) {
            if (parameter.restartRequired() && overrides.containsKey(parameter.key())) {
                command.add("-c");
                command.add(parameter.key() + "=" + overrides.get(parameter.key()));
            }
        }
        return command;
    }

    /**
     * Applies the overridden settings that need no restart, and resets the others to their
     * defaults, with {@code ALTER SYSTEM}, then reloads the configuration. Values travel as
     * psql variables, never as SQL text; the setting names come from the catalog.
     *
     * @throws IllegalStateException when Postgres rejects a value
     */
    public void applySettings(StoredDbSystem system) {
        Map<String, String> overrides = system.getConfigOverrides() != null ? system.getConfigOverrides() : Map.of();
        List<String> env = new ArrayList<>();
        StringBuilder script = new StringBuilder();
        List<String> variables = new ArrayList<>();
        int index = 0;
        for (PsqlCatalog.Parameter parameter : PsqlCatalog.PARAMETERS) {
            if (parameter.restartRequired()) {
                continue;
            }
            if (overrides.containsKey(parameter.key())) {
                String variable = "v" + index++;
                env.add("FLOCI_" + variable.toUpperCase(Locale.ROOT) + "=" + overrides.get(parameter.key()));
                variables.add(variable);
                script.append("ALTER SYSTEM SET ").append(parameter.key()).append(" = :'").append(variable)
                        .append("';\n");
            } else {
                script.append("ALTER SYSTEM RESET ").append(parameter.key()).append(";\n");
            }
        }
        script.append("SELECT pg_reload_conf();\n");
        // The script travels in an env var like the values: inline in the shell command, the
        // quotes of :'v0' would close the shell's own quoting and reach psql as :v0, unquoted.
        env.add("FLOCI_SCRIPT=" + script);
        StringBuilder command = new StringBuilder("printf '%s' \"$FLOCI_SCRIPT\" | psql -v ON_ERROR_STOP=1");
        for (String variable : variables) {
            command.append(" -v ").append(variable).append("=\"$FLOCI_").append(variable.toUpperCase(Locale.ROOT))
                    .append('"');
        }
        command.append(" -U \"$FLOCI_ROLE\" -d postgres");
        env.add("FLOCI_ROLE=" + system.getAdminUsername());
        ExecResult result = lifecycleManager.exec(system.getContainerName(), env,
                List.of("sh", "-c", command.toString()), EXEC_TIMEOUT);
        if (!result.succeeded()) {
            throw new IllegalStateException("PostgreSQL rejected the configuration (exit " + result.exitCode() + "): "
                    + result.stderr().strip());
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

    /**
     * Dumps every database and role of the running system into the emulator's dump store and
     * returns the dump's size in bytes. Role passwords stay out ({@code --no-role-passwords}), so
     * a system the dump is loaded into keeps its own.
     *
     * @throws IllegalStateException when the dump fails
     */
    public long dump(StoredDbSystem system, String dumpId) {
        String file = dumpFile(dumpId);
        try {
            ExecResult result = lifecycleManager.exec(system.getContainerName(),
                    List.of("FLOCI_ROLE=" + system.getAdminUsername(), "FLOCI_DUMP=" + file),
                    List.of("sh", "-c", "pg_dumpall --no-role-passwords -U \"$FLOCI_ROLE\""
                            + " -f \"$FLOCI_DUMP\" && stat -c %s \"$FLOCI_DUMP\""),
                    DUMP_TIMEOUT);
            if (!result.succeeded()) {
                throw new IllegalStateException("pg_dumpall failed (exit " + result.exitCode() + "): "
                        + result.stderr().strip());
            }
            long bytes = Long.parseLong(result.stdout().strip());
            lifecycleManager.copyFromContainer(system.getContainerName(), file, storedDump(dumpId));
            return bytes;
        } finally {
            removeDumpFiles(system, file);
        }
    }

    /**
     * Replaces the running system's databases with the dump {@code dumpId}, which reaches the
     * container only for the load. From {@code template1}, every database that is not a template
     * is dropped (closing its connections) and an empty {@code postgres} created; then the dump is
     * loaded. The roles the system has are kept.
     *
     * @throws IllegalStateException when the dump is missing or any part of the load fails
     */
    public void restore(StoredDbSystem system, String dumpId) {
        Path stored = storedDump(dumpId);
        if (!Files.exists(stored)) {
            throw new IllegalStateException("The dump of backup " + dumpId + " is gone");
        }
        String file = dumpFile(dumpId);
        try {
            lifecycleManager.copyToContainer(system.getContainerName(), stored, DUMP_DIR);
            ExecResult result = lifecycleManager.exec(system.getContainerName(),
                    List.of("FLOCI_ROLE=" + system.getAdminUsername(), "FLOCI_DUMP=" + file),
                    List.of("sh", "-c", RESTORE),
                    DUMP_TIMEOUT);
            if (!result.succeeded()) {
                throw new IllegalStateException("The backup could not be restored (exit " + result.exitCode() + "): "
                        + result.stderr().strip());
            }
        } finally {
            removeDumpFiles(system, file);
        }
    }

    /** Removes every stored dump: for a state reset. */
    public void removeBackups() {
        Path store = dumpsStore();
        if (!Files.exists(store)) {
            return;
        }
        try (Stream<Path> dumps = Files.list(store)) {
            for (Path dump : dumps.toList()) {
                Files.deleteIfExists(dump);
            }
            Files.deleteIfExists(store);
        } catch (IOException e) {
            throw new IllegalStateException("Could not remove " + store + ": " + e.getMessage(), e);
        }
    }

    /** With in-memory storage the backup records die with the emulator, so their dumps do too. */
    @PreDestroy
    void removeDumpsThatWouldOutliveTheirBackups() {
        if ("memory".equals(serviceConfigAccess.storageMode("psql"))) {
            try {
                removeBackups();
            } catch (RuntimeException e) {
                LOG.warnv("Could not remove the PostgreSQL dumps: {0}", e.getMessage());
            }
        }
    }

    private static String dumpFile(String dumpId) {
        return DUMP_DIR + "/" + dumpId + ".sql";
    }

    /** The dump as Docker hands it out: a tar archive holding {@code <dumpId>.sql}. */
    private Path storedDump(String dumpId) {
        return dumpsStore().resolve(dumpId + ".tar");
    }

    private Path dumpsStore() {
        return Path.of(config.storage().persistentPath(), DUMPS_STORE);
    }

    /** Best effort: the container may be gone, and a dump left in it holds only its own data. */
    private void removeDumpFiles(StoredDbSystem system, String file) {
        try {
            lifecycleManager.exec(system.getContainerName(), List.of("FLOCI_DUMP=" + file),
                    List.of("sh", "-c", "rm -f \"$FLOCI_DUMP\" \"$FLOCI_DUMP.roles\" \"$FLOCI_DUMP.load\""),
                    EXEC_TIMEOUT);
        } catch (RuntimeException e) {
            LOG.debugv("Could not remove {0} from {1}: {2}", file, system.getContainerName(), e.getMessage());
        }
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
