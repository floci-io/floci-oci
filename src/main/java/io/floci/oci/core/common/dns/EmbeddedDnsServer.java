package io.floci.oci.core.common.dns;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerDetector;
import io.quarkus.runtime.Startup;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SequencedSet;

/**
 * Embedded UDP/53 DNS server that runs inside the Floci container and is injected
 * into every spawned container (Lambda, RDS, ElastiCache) as their DNS resolver.
 *
 * Resolves *.{floci.hostname} (and any configured extra-suffixes) to Floci's own
 * Docker network IP so virtual-hosted S3 URLs (my-bucket.floci:4566) work from
 * inside Lambda containers without requiring wildcard Docker aliases.
 *
 * Names inside a zone a {@link DnsRecordSource} holds (OCI DNS zones) are answered from that source.
 * All other queries are forwarded to the upstream resolvers read from /etc/resolv.conf
 * (Docker's embedded DNS at 127.0.0.11), falling back to the configured public resolvers
 * (floci.dns.container-fallback-servers) so public hostnames still resolve when the
 * resolv.conf resolver does not answer.
 *
 * Only serves containers when Floci detects it is running inside Docker. On the host it listens
 * only when {@code floci-oci.dns.host-port} is set, on loopback, for tools such as
 * {@code dig @127.0.0.1 -p <port>}.
 */
@ApplicationScoped
@Startup
public class EmbeddedDnsServer {

    private static final Logger LOG = Logger.getLogger(EmbeddedDnsServer.class);
    private static final int DNS_PORT = 53;
    private static final int TTL = 60;
    private static final String FALLBACK_UPSTREAM = "127.0.0.11";
    private static final String LOOPBACK = "127.0.0.1";
    private static final int MAX_UDP_PAYLOAD = 512;
    // EDNS0-capable resolvers (Node/c-ares, glibc) advertise UDP payloads well above the
    // legacy 512-byte limit; CDN-backed public names return larger responses. Receiving into
    // a 512-byte buffer silently truncates the datagram and corrupts the forwarded answer.
    private static final int MAX_DNS_UDP_RESPONSE = 4096;
    // Per-upstream timeout. Bounded so trying every upstream stays under a typical 5s client
    // resolver timeout even in the worst case.
    private static final int FORWARD_TIMEOUT_MS = 1500;
    public static final String DEFAULT_SUFFIX = "localhost.floci.io";
    public static final String LOCALSTACK_SUFFIX = "localhost.localstack.cloud";

    // Well-known emulator wildcard DNS domains that always resolve to Floci's IP.
    // The suffix "localhost.X" covers "localhost.X" itself and "*.localhost.X" — it does
    // NOT cover "*.X" (e.g. "localhost.floci.io" does NOT resolve bare "*.floci.io").
    //   localhost.localstack.cloud → localhost.localstack.cloud, *.localhost.localstack.cloud
    //   localhost.floci.io         → localhost.floci.io, *.localhost.floci.io
    static final List<String> BUILTIN_SUFFIXES = List.of(DEFAULT_SUFFIX, LOCALSTACK_SUFFIX);

    private volatile String serverIp;
    private final SequencedSet<String> suffixes = new LinkedHashSet<>();
    private volatile List<String> upstreamDnsServers = List.of();
    private final Iterable<DnsRecordSource> recordSources;

    EmbeddedDnsServer(List<String> suffixes) {
        this(suffixes, List.of());
    }

    EmbeddedDnsServer(List<String> suffixes, Iterable<DnsRecordSource> recordSources) {
        this.suffixes.addAll(BUILTIN_SUFFIXES);
        this.suffixes.addAll(suffixes);
        this.recordSources = recordSources;
    }

    @Inject
    public EmbeddedDnsServer(EmulatorConfig config, ContainerDetector containerDetector, Vertx vertx,
                             Instance<DnsRecordSource> recordSources) {
        this.recordSources = recordSources;
        OptionalInt hostPort = config.dns().hostPort();
        boolean inContainer = containerDetector.isRunningInContainer();
        if (!inContainer && hostPort.isEmpty()) {
            return;
        }
        try {
            upstreamDnsServers = composeUpstreams(readResolvConfNameservers(),
                    config.dns().containerFallbackServers());
            suffixes.addAll(BUILTIN_SUFFIXES);
            config.hostname().ifPresent(suffixes::add);
            config.dns().extraSuffixes().ifPresent(suffixes::addAll);

            if (inContainer) {
                if (hostPort.isPresent()) {
                    LOG.info("floci-oci.dns.host-port only applies when floci-oci runs on the host; "
                            + "publish port 53/udp of the floci-oci container instead");
                }
                String myIp = InetAddress.getLocalHost().getHostAddress();
                bind(vertx, "0.0.0.0", DNS_PORT, myIp).onComplete(ar -> {
                    if (ar.succeeded()) {
                        serverIp = myIp;
                        LOG.infov("Embedded DNS server started on {0}:53, resolving {1} → {0}", myIp, suffixes);
                    } else {
                        LOG.warnv("Embedded DNS server failed to bind on port 53: {0}", ar.cause().getMessage());
                    }
                });
            } else {
                listenForHost(vertx, hostPort.getAsInt());
            }
        } catch (Exception e) {
            LOG.warnv("Failed to initialize embedded DNS server: {0}", e.getMessage());
        }
    }

    private void listenForHost(Vertx vertx, int port) {
        if (port < 1 || port > 65535) {
            LOG.warnv("Ignoring floci-oci.dns.host-port {0}: not a port between 1 and 65535", String.valueOf(port));
            return;
        }
        bind(vertx, LOOPBACK, port, LOOPBACK).onComplete(ar -> {
            if (ar.succeeded()) {
                LOG.infov("Embedded DNS server listening for the host on {0}:{1}", LOOPBACK, String.valueOf(port));
            } else {
                LOG.warnv("Embedded DNS server failed to bind on {0}:{1}: {2}", LOOPBACK, String.valueOf(port),
                        ar.cause().getMessage());
            }
        });
    }

    private Future<DatagramSocket> bind(Vertx vertx, String address, int port, String answerIp) {
        DatagramSocket socket = vertx.createDatagramSocket(new DatagramSocketOptions().setIpV6(false));
        return socket.listen(port, address).map(bound -> {
            bound.handler(packet -> handleQuery(vertx, bound, packet.data().getBytes(),
                    packet.sender().host(), packet.sender().port(), answerIp));
            return bound;
        });
    }

    public Optional<String> getServerIp() {
        return Optional.ofNullable(serverIp);
    }

    // ── packet handling ───────────────────────────────────────────────────────

    void handleQuery(Vertx vertx, DatagramSocket socket, byte[] data,
                     String senderHost, int senderPort, String myIp) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(data);
            short txId = buf.getShort();
            short flags = buf.getShort();
            short qdCount = buf.getShort();
            buf.getShort(); // ancount
            buf.getShort(); // nscount
            buf.getShort(); // arcount

            if ((flags & 0x8000) != 0 || qdCount < 1) {
                return; // not a standard query
            }

            int questionOffset = buf.position(); // always 12 for a standard query
            String qname = readName(buf, data);
            short qtype = buf.getShort();
            buf.getShort(); // qclass
            int questionEnd = buf.position();

            Optional<String> resolvedAddress = qtype == 1 ? resolveARecord(qname, myIp) : Optional.empty();
            if (resolvedAddress.isPresent()) {
                byte[] response = buildAResponse(data, txId, questionOffset, questionEnd, resolvedAddress.get());
                socket.send(Buffer.buffer(response), senderPort, senderHost, v -> {});
                return;
            }
            vertx.<Optional<DnsAnswer>>executeBlocking(() -> resolveFromRecordSources(qname, qtype), false)
                    .onSuccess(answer -> {
                        if (answer.isPresent()) {
                            byte[] response = buildResponse(data, txId, questionOffset, questionEnd, answer.get());
                            socket.send(Buffer.buffer(response), senderPort, senderHost, v -> {});
                        } else {
                            forwardAsync(vertx, socket, data, senderHost, senderPort);
                        }
                    })
                    .onFailure(e -> LOG.warnv("DNS record lookup failed for {0}: {1}", qname, e.getMessage()));
        } catch (Exception e) {
            LOG.debugv("DNS packet error: {0}", e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    boolean matchesSuffix(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String suffix : suffixes) {
            String s = suffix.toLowerCase();
            if (lower.equals(s) || lower.endsWith("." + s)) {
                return true;
            }
        }
        return false;
    }

    Optional<String> resolveARecord(String name, String myIp) {
        return matchesSuffix(name) ? Optional.of(myIp) : Optional.empty();
    }

    /** The first source that holds the name answers it; a failing source is skipped. */
    Optional<DnsAnswer> resolveFromRecordSources(String name, int type) {
        for (DnsRecordSource source : recordSources) {
            try {
                Optional<DnsAnswer> answer = source.resolve(name, type);
                if (answer != null && answer.isPresent()) {
                    return answer;
                }
            } catch (RuntimeException e) {
                LOG.debugv("DNS record source {0} failed to resolve {1}: {2}",
                        source.getClass().getSimpleName(), name, e.getMessage());
            }
        }
        return Optional.empty();
    }

    static String readName(ByteBuffer buf, byte[] data) {
        StringBuilder sb = new StringBuilder();
        int safety = 0;
        while (buf.hasRemaining() && safety++ < 128) {
            int len = buf.get() & 0xFF;
            if (len == 0) {
                break;
            }
            if ((len & 0xC0) == 0xC0) {
                // compression pointer
                int offset = ((len & 0x3F) << 8) | (buf.get() & 0xFF);
                ByteBuffer ptr = ByteBuffer.wrap(data);
                ptr.position(offset);
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(readName(ptr, data));
                return sb.toString();
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            byte[] label = new byte[len];
            buf.get(label);
            sb.append(new String(label));
        }
        return sb.toString();
    }

    byte[] buildAResponse(byte[] query, short txId, int questionOffset, int questionEnd, String ip) {
        int questionLength = questionEnd - questionOffset;
        // header(12) + question + answer(name-ptr(2) + type(2) + class(2) + ttl(4) + rdlen(2) + rdata(4))
        ByteBuffer resp = ByteBuffer.allocate(12 + questionLength + 16);

        // header
        resp.putShort(txId);
        resp.putShort((short) 0x8180); // QR=1, AA=1, RD=1, RCODE=0
        resp.putShort((short) 1);      // qdcount
        resp.putShort((short) 1);      // ancount
        resp.putShort((short) 0);      // nscount
        resp.putShort((short) 0);      // arcount

        // question (copied verbatim from query)
        resp.put(query, questionOffset, questionLength);

        // answer
        resp.putShort((short) 0xC00C); // name pointer to offset 12 (start of question name)
        resp.putShort((short) 1);       // type A
        resp.putShort((short) 1);       // class IN
        resp.putInt(TTL);
        resp.putShort((short) 4);       // rdlength

        for (String octet : ip.split("\\.")) {
            resp.put((byte) Integer.parseInt(octet));
        }

        return resp.array();
    }

    /**
     * An authoritative answer from a record source: each answer record under its own owner name (a
     * pointer to the question when it is the query name), and the zone's SOA in the authority section
     * whenever the source supplies one. A source supplies it only for a negative answer, which includes a
     * CNAME chain ending at a missing name or one without the asked type: the answer then holds the
     * CNAMEs, and the SOA still gives the negative-cache TTL (RFC 2308 section 2.1). Records that would
     * push the datagram past 512 bytes are left out and TC is set.
     */
    byte[] buildResponse(byte[] query, short txId, int questionOffset, int questionEnd, DnsAnswer answer) {
        ByteBuffer question = ByteBuffer.wrap(query);
        question.position(questionOffset);
        String qname = readName(question, query);
        int questionLength = questionEnd - questionOffset;
        List<byte[]> encoded = new ArrayList<>();
        int length = 12 + questionLength;
        boolean truncated = false;
        for (DnsAnswer.Record record : answer.records()) {
            byte[] entry;
            try {
                entry = resourceRecord(record.owner(), qname, record.data().type(), record.ttlSeconds(),
                        record.data().data());
            } catch (IllegalArgumentException e) {
                LOG.debugv("Skipping a DNS record for {0} that cannot be encoded: {1}", record.owner(), e.getMessage());
                continue;
            }
            if (length + entry.length > MAX_UDP_PAYLOAD) {
                truncated = true;
                break;
            }
            encoded.add(entry);
            length += entry.length;
        }
        byte[] authority = null;
        if (!truncated && answer.authority() != null) {
            DnsAnswer.Authority soa = answer.authority();
            try {
                byte[] entry = resourceRecord(soa.zone(), qname, soa.soa().type(), soa.ttlSeconds(), soa.soa().data());
                if (length + entry.length <= MAX_UDP_PAYLOAD) {
                    authority = entry;
                    length += entry.length;
                }
            } catch (IllegalArgumentException e) {
                LOG.debugv("Skipping the SOA of {0} that cannot be encoded: {1}", soa.zone(), e.getMessage());
            }
        }
        ByteBuffer resp = ByteBuffer.allocate(length);
        resp.putShort(txId);
        // QR, AA and RA, the client's RD bit, TC when records were left out, and the response code.
        int flags = 0x8480 | (query[2] & 0x01) << 8 | (truncated ? 0x0200 : 0) | answer.responseCode();
        resp.putShort((short) flags);
        resp.putShort((short) 1);
        resp.putShort((short) encoded.size());
        resp.putShort((short) (authority == null ? 0 : 1));
        resp.putShort((short) 0);
        resp.put(query, questionOffset, questionLength);
        for (byte[] entry : encoded) {
            resp.put(entry);
        }
        if (authority != null) {
            resp.put(authority);
        }
        return resp.array();
    }

    private static byte[] resourceRecord(String owner, String qname, int type, int ttl, byte[] rdata) {
        byte[] name = owner.equalsIgnoreCase(qname) ? new byte[]{(byte) 0xC0, 0x0C} : DnsRecord.encodeName(owner);
        return ByteBuffer.allocate(name.length + 10 + rdata.length)
                .put(name).putShort((short) type).putShort((short) 1).putInt(ttl)
                .putShort((short) rdata.length).put(rdata).array();
    }

    private void forwardAsync(Vertx vertx, DatagramSocket socket, byte[] query,
                              String senderHost, int senderPort) {
        List<String> upstreams = upstreamDnsServers;
        if (upstreams.isEmpty()) {
            return;
        }
        vertx.executeBlocking(() -> forwardToUpstreams(query, upstreams, DNS_PORT))
                .onSuccess(response ->
                        socket.send(Buffer.buffer(response), senderPort, senderHost, v -> {}))
                .onFailure(e ->
                        LOG.warnv("DNS forwarding failed on all upstreams {0}: {1}",
                                upstreams, e.getMessage()));
    }

    /**
     * Forwards the query to each upstream in order and returns the first valid UDP response.
     * Throws if every upstream times out or errors, so the caller can log a single warning.
     * The {@code upstreamPort} is parameterised for tests; production always uses {@link #DNS_PORT}.
     */
    byte[] forwardToUpstreams(byte[] query, List<String> upstreams, int upstreamPort) throws Exception {
        Exception last = null;
        for (String upstream : upstreams) {
            // Qualified: the imported DatagramSocket is the Vert.x one.
            try (java.net.DatagramSocket fwd = new java.net.DatagramSocket()) {
                fwd.setSoTimeout(FORWARD_TIMEOUT_MS);
                InetAddress addr = InetAddress.getByName(upstream);
                fwd.send(new DatagramPacket(query, query.length, addr, upstreamPort));
                byte[] buf = new byte[MAX_DNS_UDP_RESPONSE];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                fwd.receive(resp);
                return Arrays.copyOf(resp.getData(), resp.getLength());
            } catch (Exception e) {
                last = e;
                LOG.debugv("DNS forward to {0} failed: {1}", upstream, e.getMessage());
            }
        }
        throw last != null ? last : new IOException("no upstream resolvers configured");
    }

    /**
     * Builds the ordered, de-duplicated upstream list the forwarder tries in turn: the
     * resolver(s) from {@code /etc/resolv.conf} first (or Docker's embedded resolver as a
     * baseline when none are usable), then the configured public fallbacks. The fallbacks let
     * public names resolve even when the resolv.conf resolver does not answer, mirroring the
     * {@code --dns <FlociIP> --dns 8.8.8.8} workaround.
     */
    static List<String> composeUpstreams(List<String> resolvConf, List<String> fallbacks) {
        SequencedSet<String> ordered = new LinkedHashSet<>();
        for (String server : resolvConf) {
            if (isUsableUpstream(server)) {
                ordered.add(server.trim());
            }
        }
        if (ordered.isEmpty()) {
            ordered.add(FALLBACK_UPSTREAM);
        }
        if (fallbacks != null) {
            for (String server : fallbacks) {
                if (isUsableUpstream(server)) {
                    ordered.add(server.trim());
                }
            }
        }
        return List.copyOf(ordered);
    }

    private static boolean isUsableUpstream(String server) {
        return server != null && !server.isBlank() && !server.trim().equals("127.0.0.1");
    }

    private List<String> readResolvConfNameservers() {
        List<String> servers = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(Path.of("/etc/resolv.conf"))) {
                line = line.trim();
                if (line.startsWith("nameserver ")) {
                    servers.add(line.substring("nameserver ".length()).trim());
                }
            }
        } catch (Exception e) {
            LOG.debugv("Could not read /etc/resolv.conf: {0}", e.getMessage());
        }
        return servers;
    }
}
