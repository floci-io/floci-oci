package io.floci.oci.services.streaming;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerDetector;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.ExecResult;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.core.common.docker.CurrentContainerNetworkResolver;
import io.floci.oci.core.common.docker.PortAllocator;
import io.floci.oci.services.streaming.model.StoredStream.StoredEntry;
import io.floci.oci.services.streaming.model.StoredStreamPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs one Redpanda broker per stream pool. It owns only the mechanics: it never reads the
 * {@code mock} flag (the service gates every call) and knows nothing about lifecycle states or
 * work requests.
 *
 * <p>The broker has two Kafka listeners. {@code external} (9092) is published on a host port and
 * advertises the address Kafka clients are given as the pool's {@code bootstrapServers}.
 * {@code internal} (29092) advertises loopback, for the HTTP proxy and {@code rpk} inside the
 * container: a client is always handed the address of the listener it connected through, and
 * the external address does not resolve from inside the container.
 */
@ApplicationScoped
public class StreamPoolKafkaManager {

    private static final Logger LOG = Logger.getLogger(StreamPoolKafkaManager.class);

    static final int KAFKA_PORT = 9092;
    static final int INTERNAL_KAFKA_PORT = 29092;
    static final int PROXY_PORT = 8082;
    private static final int SHORT_ID_LENGTH = 16;
    private static final String DATA_PATH = "/var/lib/redpanda/data";
    private static final String RPK_BROKERS = "brokers=127.0.0.1:" + INTERNAL_KAFKA_PORT;
    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(20);
    private static final String RECORD_FORMAT = "%o\\t%d\\t%k{base64}\\t%v{base64}\\n";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final CurrentContainerNetworkResolver networkResolver;
    private final PortAllocator portAllocator;
    private final KafkaRestClient kafkaRest;
    private final EmulatorConfig config;

    @Inject
    public StreamPoolKafkaManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                  ContainerDetector containerDetector,
                                  CurrentContainerNetworkResolver networkResolver, PortAllocator portAllocator,
                                  KafkaRestClient kafkaRest, EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.networkResolver = networkResolver;
        this.portAllocator = portAllocator;
        this.kafkaRest = kafkaRest;
        this.config = config;
    }

    /** The broker's container name: the end of the OCID's unique part stands in for the OCID. */
    public String containerName(String streamPoolId) {
        return ContainerStorageHelper.dockerName(config, "streaming-" + shortId(streamPoolId));
    }

    public String volumeName(String streamPoolId) {
        return ContainerStorageHelper.dockerName(config, "streaming-vol-" + shortId(streamPoolId));
    }

    private static String shortId(String streamPoolId) {
        return streamPoolId.substring(Math.max(0, streamPoolId.length() - SHORT_ID_LENGTH));
    }

    /**
     * Starts the pool's broker and records its host port and proxy endpoint on {@code pool}.
     * The topics live on a named volume, so a broker started again for the same pool finds
     * them. On failure the container is removed and the port released before the exception
     * is rethrown.
     */
    public void start(StoredStreamPool pool) {
        String name = containerName(pool.getId());
        lifecycleManager.removeIfExists(name);
        int hostPort = claimPort(pool.getKafkaHostPort());
        try {
            String volume = volumeName(pool.getId());
            lifecycleManager.ensureVolume(volume);
            EndpointInfo advertised = lifecycleManager.clientEndpoint(name, KAFKA_PORT, hostPort);
            ContainerSpec spec = containerBuilder.newContainer(config.services().streaming().kafkaImage())
                    .withName(name)
                    .withCmd(List.of(
                            "redpanda", "start", "--overprovisioned", "--smp", "1",
                            "--memory", "512M", "--reserve-memory", "0M",
                            "--kafka-addr", "internal://0.0.0.0:" + INTERNAL_KAFKA_PORT
                                    + ",external://0.0.0.0:" + KAFKA_PORT,
                            "--advertise-kafka-addr", "internal://127.0.0.1:" + INTERNAL_KAFKA_PORT
                                    + ",external://" + advertised,
                            "--pandaproxy-addr", "0.0.0.0:" + PROXY_PORT,
                            "--set", "pandaproxy_client.brokers=[{\"address\":\"127.0.0.1\",\"port\":"
                                    + INTERNAL_KAFKA_PORT + "}]"))
                    .withPortBinding(KAFKA_PORT, hostPort)
                    .withDynamicPort(PROXY_PORT)
                    .withNamedVolume(volume, DATA_PATH)
                    .withDockerNetwork(Optional.empty())
                    .withLogRotation()
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                            "streaming", pool.getId(), pool.getCompartmentId(), pool.getRegion()))
                    .build();
            ContainerInfo info = lifecycleManager.createAndStart(spec);
            pool.setKafkaHostPort(hostPort);
            pool.setProxyEndpoint(proxyEndpoint(info.containerId(), info.getEndpoint(PROXY_PORT)));
            pool.setBrokerStartedAt(Instant.now().toString());
            LOG.infov("Started Redpanda for stream pool {0}: Kafka at {1}, proxy at {2}",
                    pool.getId(), advertised, pool.getProxyEndpoint());
        } catch (RuntimeException e) {
            lifecycleManager.removeIfExists(name);
            portAllocator.release(hostPort);
            throw e;
        }
    }

    /**
     * Where floci-oci reaches the proxy. In a container that is the broker's IP on the network
     * they share; on the host it is the published port on the Docker daemon's host.
     */
    private String proxyEndpoint(String containerId, EndpointInfo resolved) {
        if (!containerDetector.isRunningInContainer()) {
            return lifecycleManager.daemonHost() + ":" + resolved.port();
        }
        String network = config.services().dockerNetwork()
                .filter(n -> !n.isBlank())
                .or(networkResolver::resolveNetworkName)
                .orElse(null);
        EndpointInfo info = lifecycleManager.resolveEndpoint(containerId, PROXY_PORT, network);
        String host = info.host().contains(":") ? "[" + info.host() + "]" : info.host();
        return host + ":" + info.port();
    }

    /** The {@code bootstrapServers} Kafka clients are given for the pool. */
    public String bootstrapServers(StoredStreamPool pool) {
        return lifecycleManager.clientEndpoint(containerName(pool.getId()), KAFKA_PORT, pool.getKafkaHostPort())
                .toString();
    }

    /** One cheap readiness probe: the proxy answers and reaches the broker. */
    public boolean isReady(StoredStreamPool pool) {
        return pool.getProxyEndpoint() != null && kafkaRest.isReady(proxyUri(pool));
    }

    public boolean isRunning(StoredStreamPool pool) {
        return lifecycleManager.isContainerRunning(containerName(pool.getId()));
    }

    /**
     * Creates the stream's topic with the stream's partitions and retention. A topic that
     * already exists (a broker started again over its volume) is left as it is.
     *
     * @throws IllegalStateException when the broker refuses the topic
     */
    public void createTopic(StoredStreamPool pool, String topic, int partitions, int retentionHours) {
        ExecResult result = rpk(pool, "topic", "create", topic, "-p", String.valueOf(partitions), "-r", "1",
                "-c", "retention.ms=" + Duration.ofHours(retentionHours).toMillis());
        if (!result.succeeded() && !result.stdout().contains("TOPIC_ALREADY_EXISTS")) {
            throw new IllegalStateException("Redpanda refused topic " + topic + " (exit " + result.exitCode()
                    + "): " + (result.stdout() + result.stderr()).strip());
        }
    }

    /**
     * Deletes the stream's topic.
     *
     * @return true once the broker no longer has the topic, including when it never had it
     */
    public boolean deleteTopic(StoredStreamPool pool, String topic) {
        ExecResult result = rpk(pool, "topic", "delete", topic);
        if (topicGone(result)) {
            return true;
        }
        LOG.warnv("Could not delete topic {0} of stream pool {1} (exit {2}): {3}", topic, pool.getId(),
                result.exitCode(), (result.stdout() + result.stderr()).strip());
        return false;
    }

    /**
     * Whether {@code rpk topic delete} left the broker without the topic. It prints one
     * {@code TOPIC STATUS} row per topic: {@code OK}, or the Kafka error, which for a topic that
     * does not exist is {@code UNKNOWN_TOPIC_OR_PARTITION}.
     */
    static boolean topicGone(ExecResult result) {
        String output = result.stdout() + result.stderr();
        if (output.contains("UNKNOWN_TOPIC_OR_PARTITION")) {
            return true;
        }
        if (!result.succeeded()) {
            return false;
        }
        String[] lines = result.stdout().strip().split("\n");
        for (int i = 1; i < lines.length; i++) {
            String[] row = lines[i].trim().split("\\s+", 2);
            if (row.length < 2 || !"OK".equals(row[1].trim())) {
                return false;
            }
        }
        return true;
    }

    public List<KafkaRestClient.RecordOffset> produce(StoredStreamPool pool, String topic,
                                                      List<KafkaRestClient.ProducedRecord> records) {
        return kafkaRest.produce(proxyUri(pool), topic, records);
    }

    /**
     * Up to {@code max} records of one partition from {@code offset}, through {@code rpk}, which
     * stops at the partition's current end and, unlike the HTTP proxy, returns each record's
     * timestamp.
     */
    public List<StoredEntry> read(StoredStreamPool pool, String topic, int partition, long offset, int max) {
        ExecResult result = rpk(pool, "topic", "consume", topic, "-p", String.valueOf(partition),
                "-o", offset + ":end", "-n", String.valueOf(max), "-f", RECORD_FORMAT);
        if (!result.succeeded()) {
            throw new IllegalStateException("Could not read topic " + topic + " (exit " + result.exitCode() + "): "
                    + result.stderr().strip());
        }
        List<StoredEntry> entries = new ArrayList<>();
        for (String line : result.stdout().split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length < 4) {
                throw new IllegalStateException("Unexpected rpk record line: " + line);
            }
            StoredEntry entry = new StoredEntry();
            entry.setOffset(Long.parseLong(fields[0]));
            entry.setTimestamp(Instant.ofEpochMilli(Long.parseLong(fields[1])).toString());
            entry.setKey(fields[2].isEmpty() ? null : fields[2]);
            entry.setValue(fields[3]);
            entries.add(entry);
        }
        return entries;
    }

    /** The high watermark of one partition: the offset its next record gets. */
    public long endOffset(StoredStreamPool pool, String topic, int partition) {
        ExecResult result = rpk(pool, "topic", "describe", topic, "-p");
        if (!result.succeeded()) {
            throw new IllegalStateException("Could not describe topic " + topic + " (exit " + result.exitCode()
                    + "): " + result.stderr().strip());
        }
        return highWatermark(result.stdout(), partition);
    }

    /** Reads the HIGH-WATERMARK column of {@code partition}'s row in {@code rpk topic describe -p}. */
    static long highWatermark(String describe, int partition) {
        String[] lines = describe.strip().split("\n");
        List<String> header = List.of(lines[0].trim().split("\\s+"));
        int partitionColumn = header.indexOf("PARTITION");
        int watermarkColumn = header.indexOf("HIGH-WATERMARK");
        if (partitionColumn < 0 || watermarkColumn < 0) {
            throw new IllegalStateException("Unexpected rpk topic describe output: " + describe);
        }
        for (int i = 1; i < lines.length; i++) {
            String[] row = lines[i].trim().split("\\s+");
            if (row.length > watermarkColumn && row[partitionColumn].equals(String.valueOf(partition))) {
                return Long.parseLong(row[watermarkColumn]);
            }
        }
        throw new IllegalStateException("Partition " + partition + " is not in: " + describe);
    }

    /** Removes the container and releases its port. The data volume stays. */
    public void stop(StoredStreamPool pool) {
        lifecycleManager.removeIfExists(containerName(pool.getId()));
        if (pool.getKafkaHostPort() > 0) {
            portAllocator.release(pool.getKafkaHostPort());
        }
    }

    /**
     * Stops the container and removes its data volume, under the same policy as the other
     * sidecars: in {@code memory} storage mode, or when {@code prune-volumes-on-delete} is set.
     */
    public void remove(StoredStreamPool pool) {
        stop(pool);
        if ("memory".equalsIgnoreCase(config.storage().mode()) || config.storage().pruneVolumesOnDelete()) {
            lifecycleManager.removeVolume(volumeName(pool.getId()));
        }
    }

    /** Stops the container and always removes its data volume: for a state reset. */
    public void purge(StoredStreamPool pool) {
        stop(pool);
        lifecycleManager.removeVolume(volumeName(pool.getId()));
    }

    private ExecResult rpk(StoredStreamPool pool, String... args) {
        List<String> command = new ArrayList<>(List.of("rpk", "-X", RPK_BROKERS));
        command.addAll(List.of(args));
        return lifecycleManager.exec(containerName(pool.getId()), List.of(), command, EXEC_TIMEOUT);
    }

    private static URI proxyUri(StoredStreamPool pool) {
        return URI.create("http://" + pool.getProxyEndpoint() + "/");
    }

    /**
     * The port the pool had before, so its endpoint survives a restart, unless this process, a
     * running container or a local listener holds it now; otherwise a fresh port in the range.
     */
    int claimPort(int previous) {
        if (previous > 0 && portAllocator.reclaim(previous)) {
            return previous;
        }
        return portAllocator.allocate(config.services().streaming().kafkaBasePort(),
                config.services().streaming().kafkaMaxPort());
    }
}
