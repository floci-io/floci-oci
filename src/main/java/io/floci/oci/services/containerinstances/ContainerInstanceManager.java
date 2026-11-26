package io.floci.oci.services.containerinstances;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.core.common.docker.ImageCacheService;
import io.floci.oci.services.containerinstances.model.StoredContainer;
import io.floci.oci.services.containerinstances.model.StoredContainerInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Runs a container instance as a pod on Docker: one pause container holds the network namespace
 * on the shared Docker network (so the instance has one IP, like its one VNIC), and each container
 * joins it with {@code --network container:<pause>}, the way containers of one OCI container
 * instance share localhost. Every volume of the instance is one named volume shared by the
 * containers that mount it: EMPTYDIR on disk, or on tmpfs for MEMORY, and CONFIGFILE seeded with
 * its files before any container starts, so {@code isReadOnly} is a read-only mount for each of
 * them. Flag-free mechanics only: the service decides when any of this runs.
 */
@ApplicationScoped
public class ContainerInstanceManager {

    private static final Logger LOG = Logger.getLogger(ContainerInstanceManager.class);
    private static final String SERVICE = "containerinstances";
    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;
    private static final int LOG_LIMIT_BYTES = 20 * 1024 * 1024;
    private static final Map<String, String> TMPFS = Map.of("type", "tmpfs", "device", "tmpfs");
    private static final String SEED_MOUNT = "/floci-config";

    /** What Docker reports for one container. */
    record ContainerState(boolean running, Integer exitCode, Instant finishedAt) {
    }

    private final EmulatorConfig config;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycle;
    private final ImageCacheService images;

    @Inject
    public ContainerInstanceManager(EmulatorConfig config, ContainerBuilder containerBuilder,
                                    ContainerLifecycleManager lifecycle, ImageCacheService images) {
        this.config = config;
        this.containerBuilder = containerBuilder;
        this.lifecycle = lifecycle;
        this.images = images;
    }

    String podName(StoredContainerInstance instance) {
        return ContainerStorageHelper.dockerName(config, "ci-" + instance.getId());
    }

    String containerName(StoredContainer container) {
        return ContainerStorageHelper.dockerName(config, "ci-" + container.getId());
    }

    /**
     * The Docker volume of one instance volume. OCI volume names are case-sensitive and may hold
     * characters Docker rejects, so the readable part is followed by a hash of the exact name:
     * {@code Cache} and {@code cache} are two volumes.
     */
    String volumeName(StoredContainerInstance instance, String volume) {
        String safe = volume.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
        return ContainerStorageHelper.dockerName(config, "ci-" + instance.getId() + "-" + safe + "-" + shortHash(volume));
    }

    private static String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** The configured gracefulShutdownTimeoutInSeconds, as the Docker stop timeout. */
    static int shutdownTimeout(StoredContainerInstance instance) {
        if (instance.getAttributes().get("gracefulShutdownTimeoutInSeconds") instanceof Number seconds) {
            return (int) Math.clamp(seconds.longValue(), 0, Integer.MAX_VALUE);
        }
        return 0;
    }

    /**
     * Pulls every image, starts the pause container and then the containers, in order. Returns the
     * pod's IP address on its Docker network, or null when Docker reports none. On failure what
     * was started is removed again and the exception propagates.
     *
     * @param pullable turns a stored image reference into one the daemon can pull (local OCIR)
     */
    String startPod(StoredContainerInstance instance, List<StoredContainer> containers,
                    UnaryOperator<String> pullable) {
        try {
            for (StoredContainer container : containers) {
                pull(instance, pullable.apply(container.getImageUrl()));
            }
            String pause = podName(instance);
            lifecycle.removeIfExists(pause);
            ContainerSpec pauseSpec = containerBuilder.newContainer(config.services().containerinstances().pauseImage())
                    .withName(pause)
                    .withDockerNetwork(Optional.empty())
                    .withLabels(labels(instance, instance.getId()))
                    .build();
            lifecycle.createAndStart(pauseSpec);
            seedConfigVolumes(instance);
            for (StoredContainer container : containers) {
                createAndStart(instance, container, pullable);
            }
            return podIp(pause, pauseSpec.networkMode());
        } catch (RuntimeException e) {
            try {
                removePod(instance, containers);
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /**
     * Writes the files of every CONFIGFILE volume into its named volume, through a container that
     * is created (never started) with the volume mounted writable and removed again. A helper
     * Docker cannot remove fails the start, and {@link #removePod} removes it before the volume.
     */
    private void seedConfigVolumes(StoredContainerInstance instance) {
        for (Map<String, Object> volume : mapList(instance.getAttributes().get("volumes"))) {
            if (!"CONFIGFILE".equals(volume.get("volumeType"))) {
                continue;
            }
            String volumeName = volumeName(instance, String.valueOf(volume.get("name")));
            lifecycle.ensureVolume(volumeName);
            Map<String, byte[]> files = new LinkedHashMap<>();
            for (Map<String, Object> file : mapList(volume.get("configs"))) {
                String directory = file.get("path") instanceof String path ? SEED_MOUNT + "/" + path : SEED_MOUNT;
                files.put(directory + "/" + file.get("fileName"),
                        Base64.getDecoder().decode(String.valueOf(file.get("data"))));
            }
            if (files.isEmpty()) {
                continue;
            }
            String seed = seedName(instance, String.valueOf(volume.get("name")));
            lifecycle.forceRemove(seed);
            ContainerSpec spec = containerBuilder.newContainer(config.services().containerinstances().pauseImage())
                    .withName(seed)
                    .withNetworkMode("none")
                    .withNamedVolume(volumeName, SEED_MOUNT, false)
                    .withLabels(labels(instance, instance.getId()))
                    .build();
            String id = lifecycle.create(spec);
            try {
                copyFiles(id, files);
            } catch (RuntimeException e) {
                try {
                    lifecycle.forceRemove(seed);
                } catch (RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
            lifecycle.forceRemove(seed);
        }
    }

    /** The helper that seeds a CONFIGFILE volume: one fixed name per instance and volume. */
    String seedName(StoredContainerInstance instance, String volume) {
        return volumeName(instance, volume) + "-seed";
    }

    private void pull(StoredContainerInstance instance, String image) {
        String host = registryHost(image);
        for (StoredContainerInstance.PullCredential credential : instance.getPullCredentials()) {
            if (registryHost(credential.registryEndpoint() + "/x").equals(host)) {
                images.ensureImageExists(image, credential.username(), credential.password());
                return;
            }
        }
        images.ensureImageExists(image);
    }

    /** The registry part of an image reference, or {@code docker.io} for a Docker Hub image. */
    static String registryHost(String image) {
        String stripped = image.replaceFirst("^[a-z]+://", "");
        int slash = stripped.indexOf('/');
        String first = slash < 0 ? "" : stripped.substring(0, slash);
        boolean registry = first.contains(".") || first.contains(":") || first.equals("localhost");
        return registry ? first : "docker.io";
    }

    private void createAndStart(StoredContainerInstance instance, StoredContainer container,
                                UnaryOperator<String> pullable) {
        String name = containerName(container);
        lifecycle.removeIfExists(name);
        Map<String, Object> a = container.getAttributes();
        ContainerBuilder.Builder builder = containerBuilder.newContainer(pullable.apply(container.getImageUrl()))
                .withName(name)
                .withNetworkMode("container:" + podName(instance))
                .withLogRotation()
                .withLabels(labels(instance, container.getId()));
        List<String> command = stringList(a.get("command"));
        if (!command.isEmpty()) {
            builder.withEntrypoint(command);
        }
        List<String> arguments = stringList(a.get("arguments"));
        if (!arguments.isEmpty()) {
            builder.withCmd(arguments);
        }
        if (a.get("workingDirectory") instanceof String workingDirectory) {
            builder.withWorkingDir(workingDirectory);
        }
        if (a.get("environmentVariables") instanceof Map<?, ?> environment) {
            environment.forEach((key, value) -> builder.withEnv(String.valueOf(key), String.valueOf(value)));
        }
        if (a.get("resourceConfig") instanceof Map<?, ?> limits) {
            if (limits.get("memoryLimitInGBs") instanceof Number memory) {
                builder.withMemoryBytes((long) (memory.doubleValue() * BYTES_PER_GB));
            }
            if (limits.get("vcpusLimit") instanceof Number vcpus) {
                builder.withNanoCpus((long) (vcpus.doubleValue() * 1_000_000_000L));
            }
        }
        for (Map<String, Object> mount : mapList(a.get("volumeMounts"))) {
            String mountPath = String.valueOf(mount.get("mountPath"));
            Map<String, Object> volume = volume(instance, String.valueOf(mount.get("volumeName")));
            boolean readOnly = Boolean.TRUE.equals(mount.get("isReadOnly"));
            String volumeName = volumeName(instance, String.valueOf(volume.get("name")));
            switch (String.valueOf(volume.get("volumeType"))) {
                case "EMPTYDIR" -> lifecycle.ensureVolume(volumeName,
                        "MEMORY".equals(volume.get("backingStore")) ? TMPFS : Map.of());
                case "CONFIGFILE" -> lifecycle.ensureVolume(volumeName);
                default -> throw new IllegalStateException("unhandled volume type " + volume.get("volumeType"));
            }
            builder.withNamedVolume(volumeName, mountPath, readOnly);
        }
        ContainerSpec spec = builder.build();
        String id = lifecycle.create(spec);
        lifecycle.startCreated(id, spec);
        container.setDockerName(name);
        LOG.infov("Started container {0} of container instance {1}", container.getId(), instance.getId());
    }

    private void copyFiles(String containerId, Map<String, byte[]> files) {
        Path archive = null;
        try {
            archive = Files.createTempFile("floci-ci-config", ".tar");
            Files.write(archive, tar(files));
            lifecycle.copyToContainer(containerId, archive, "/");
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the config files of " + containerId + ": "
                    + e.getMessage(), e);
        } finally {
            if (archive != null) {
                try {
                    Files.deleteIfExists(archive);
                } catch (IOException e) {
                    LOG.debugv("Could not delete {0}: {1}", archive, e.getMessage());
                }
            }
        }
    }

    /** A POSIX ustar archive of {@code files}, keyed by absolute path; Docker creates the parents. */
    static byte[] tar(Map<String, byte[]> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long mtime = Instant.now().getEpochSecond();
        files.forEach((path, data) -> {
            String name = path.replaceAll("/+", "/").replaceFirst("^/", "");
            byte[] header = new byte[512];
            String prefix = "";
            if (name.length() > 100) {
                int split = name.lastIndexOf('/', 155);
                if (split <= 0 || name.length() - split - 1 > 100) {
                    throw new IllegalArgumentException("Config file path too long: " + path);
                }
                prefix = name.substring(0, split);
                name = name.substring(split + 1);
            }
            put(header, 0, name, 100);
            put(header, 100, octal(0644, 7), 8);
            put(header, 108, octal(0, 7), 8);
            put(header, 116, octal(0, 7), 8);
            put(header, 124, octal(data.length, 11), 12);
            put(header, 136, octal(mtime, 11), 12);
            for (int i = 148; i < 156; i++) {
                header[i] = ' ';
            }
            header[156] = '0';
            put(header, 257, "ustar", 6);
            put(header, 263, "00", 2);
            put(header, 345, prefix, 155);
            long checksum = 0;
            for (byte b : header) {
                checksum += b & 0xFF;
            }
            put(header, 148, octal(checksum, 6), 6);
            header[154] = 0;
            header[155] = ' ';
            out.writeBytes(header);
            out.writeBytes(data);
            int padding = (512 - data.length % 512) % 512;
            out.writeBytes(new byte[padding]);
        });
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    private static String octal(long value, int width) {
        StringBuilder text = new StringBuilder(Long.toOctalString(value));
        while (text.length() < width) {
            text.insert(0, '0');
        }
        return text.toString();
    }

    private static void put(byte[] header, int offset, String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
    }

    private static Map<String, Object> volume(StoredContainerInstance instance, String name) {
        return mapList(instance.getAttributes().get("volumes")).stream()
                .filter(volume -> name.equals(volume.get("name")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown volume " + name));
    }

    /** The pause container's address on {@code network}, or on any network it is attached to. */
    private String podIp(String pause, String network) {
        Optional<InspectContainerResponse> found = lifecycle.inspect(pause);
        if (found.isEmpty()) {
            return null;
        }
        InspectContainerResponse inspect = found.get();
        if (inspect.getNetworkSettings() == null || inspect.getNetworkSettings().getNetworks() == null) {
            return null;
        }
        Map<String, ContainerNetwork> networks = inspect.getNetworkSettings().getNetworks();
        ContainerNetwork preferred = network != null ? networks.get(network) : null;
        if (preferred != null && preferred.getIpAddress() != null && !preferred.getIpAddress().isBlank()) {
            return preferred.getIpAddress();
        }
        return networks.values().stream()
                .map(ContainerNetwork::getIpAddress)
                .filter(ip -> ip != null && !ip.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * Stops the containers, each with the instance's gracefulShutdownTimeoutInSeconds, then the
     * pause container; nothing is removed. A container that is gone or already stopped is fine;
     * any other failure is thrown once every container has been tried.
     */
    void stopPod(StoredContainerInstance instance, List<StoredContainer> containers) {
        int timeout = shutdownTimeout(instance);
        Failures failures = new Failures();
        for (StoredContainer container : containers) {
            failures.attempt(() -> stop(containerName(container), timeout));
        }
        failures.attempt(() -> stop(podName(instance), timeout));
        failures.throwIfAny();
    }

    private void stop(String name, int timeout) {
        try {
            lifecycle.stop(name, timeout);
        } catch (NotFoundException e) {
            LOG.debugv("Container {0} is already gone", name);
        } catch (NotModifiedException e) {
            LOG.debugv("Container {0} is already stopped", name);
        }
    }

    /**
     * Starts a stopped pod again: the pause container, then each container. A pod whose pause
     * container is gone is created from scratch. Returns the pod's IP address.
     */
    String restartPod(StoredContainerInstance instance, List<StoredContainer> containers,
                      UnaryOperator<String> pullable) {
        String pause = podName(instance);
        boolean intact = lifecycle.findByName(pause).isPresent()
                && containers.stream().allMatch(container -> lifecycle.findByName(containerName(container)).isPresent());
        if (!intact) {
            return startPod(instance, containers, pullable);
        }
        start(pause);
        for (StoredContainer container : containers) {
            start(containerName(container));
        }
        return podIp(pause, null);
    }

    /** Starts a stopped container; one that already runs is fine, any other failure propagates. */
    private void start(String name) {
        try {
            lifecycle.start(name);
        } catch (NotModifiedException e) {
            LOG.debugv("Container {0} is already running", name);
        }
    }

    /**
     * Restarts one container (RestartContainerInstance, and the restart policy), giving it the
     * instance's gracefulShutdownTimeoutInSeconds to stop.
     */
    void restartContainer(StoredContainerInstance instance, StoredContainer container) {
        lifecycle.restart(containerName(container), shutdownTimeout(instance));
    }

    /**
     * Removes the containers, the pause container and the instance's volumes. What is already
     * gone is fine; any other failure is thrown once everything has been tried.
     */
    void removePod(StoredContainerInstance instance, List<StoredContainer> containers) {
        Failures failures = new Failures();
        for (StoredContainer container : containers) {
            failures.attempt(() -> lifecycle.forceRemove(containerName(container)));
        }
        failures.attempt(() -> lifecycle.forceRemove(podName(instance)));
        for (Map<String, Object> volume : mapList(instance.getAttributes().get("volumes"))) {
            String name = String.valueOf(volume.get("name"));
            if ("CONFIGFILE".equals(volume.get("volumeType"))) {
                failures.attempt(() -> lifecycle.forceRemove(seedName(instance, name)));
            }
            failures.attempt(() -> lifecycle.deleteVolume(volumeName(instance, name)));
        }
        failures.throwIfAny();
    }

    /** Runs every step and keeps the first failure, with the later ones suppressed on it. */
    private static final class Failures {
        private RuntimeException first;

        void attempt(Runnable step) {
            try {
                step.run();
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }

        void throwIfAny() {
            if (first != null) {
                throw first;
            }
        }
    }

    /** What Docker reports for the container, or empty when it does not exist. */
    Optional<ContainerState> state(StoredContainer container) {
        return lifecycle.inspect(containerName(container)).map(inspect -> {
            InspectContainerResponse.ContainerState state = inspect.getState();
            boolean running = Boolean.TRUE.equals(state.getRunning());
            Long exitCode = state.getExitCodeLong();
            Instant finishedAt = null;
            String finished = state.getFinishedAt();
            if (!running && finished != null && !finished.startsWith("0001-")) {
                finishedAt = Instant.parse(finished);
            }
            return new ContainerState(running, running || exitCode == null ? null : exitCode.intValue(), finishedAt);
        });
    }

    /** The container's stdout and stderr, interleaved as Docker kept them, at most 20 MB. */
    String logs(StoredContainer container) {
        return lifecycle.logs(containerName(container), LOG_LIMIT_BYTES, Duration.ofSeconds(30));
    }

    private Map<String, String> labels(StoredContainerInstance instance, String resourceId) {
        return ContainerStorageHelper.resourceIdentityLabels(SERVICE, resourceId, instance.getCompartmentId(),
                instance.getRegion());
    }

    private static List<String> stringList(Object value) {
        List<String> strings = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                strings.add(String.valueOf(item));
            }
        }
        return strings;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mapList(Object value) {
        List<Map<String, Object>> maps = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?>) {
                    maps.add((Map<String, Object>) item);
                }
            }
        }
        return maps;
    }
}
