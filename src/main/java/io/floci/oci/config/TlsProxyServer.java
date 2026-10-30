package io.floci.oci.config;

import io.quarkus.runtime.Startup;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A TCP proxy that serves HTTP and HTTPS on the same port.
 *
 * <p>When TLS is enabled, Quarkus serves HTTP on an internal port (4510) and HTTPS on another
 * internal port (4511). This proxy listens on the public floci-oci port (4599 by default) and
 * inspects the first byte of each incoming connection:
 * <ul>
 *   <li>{@code 0x16} (TLS ClientHello) → proxy to the HTTPS backend (port 4511)</li>
 *   <li>Anything else → proxy to the HTTP backend (port 4510)</li>
 * </ul>
 * so both {@code http://localhost:4599} and {@code https://localhost:4599} work.
 *
 * <p>The proxy starts with the application, before Quarkus binds its two backends. A connection
 * that arrives in that window waits for its backend, retrying the connect for up to
 * {@link #BACKEND_WAIT}, instead of being closed: a client that saw the health endpoint answer
 * over HTTP can start a TLS handshake straight away.
 *
 * <p>The same protocol-detecting handler is also bound on {@code floci-oci.tls.https-port} (443
 * by default), for clients that assume OCI on 443. The extra binding is skipped when the port is
 * {@code 0} or equals the public port.
 *
 * <p>This bean is only active when {@code floci-oci.tls.enabled=true}. When TLS is disabled,
 * Quarkus serves HTTP directly on the public port and this proxy is not started.
 */
@ApplicationScoped
@Startup
public class TlsProxyServer {

    private static final Logger LOG = Logger.getLogger(TlsProxyServer.class);

    /** TLS record content type for Handshake (ClientHello). */
    private static final byte TLS_HANDSHAKE = 0x16;

    private static final int HTTP_BACKEND_PORT = 4510;
    private static final int HTTPS_BACKEND_PORT = 4511;

    /** How long a connection waits for its backend to accept, covering the start-up window. */
    static final Duration BACKEND_WAIT = Duration.ofSeconds(3);
    private static final long BACKEND_RETRY_MILLIS = 50;

    private final Vertx vertx;
    private final EmulatorConfig config;
    private final int httpBackendPort;
    private final int httpsBackendPort;
    private final Duration backendWait;
    private final List<NetServer> proxyServers = new ArrayList<>();
    private NetClient client;

    @Inject
    public TlsProxyServer(Vertx vertx, EmulatorConfig config) {
        this(vertx, config, HTTP_BACKEND_PORT, HTTPS_BACKEND_PORT);
    }

    /** Visible for testing: lets tests point the proxy at backends on non-default ports. */
    TlsProxyServer(Vertx vertx, EmulatorConfig config, int httpBackendPort, int httpsBackendPort) {
        this(vertx, config, httpBackendPort, httpsBackendPort, BACKEND_WAIT);
    }

    /** Visible for testing: also shortens how long a connection waits for its backend. */
    TlsProxyServer(Vertx vertx, EmulatorConfig config, int httpBackendPort, int httpsBackendPort,
                   Duration backendWait) {
        this.vertx = vertx;
        this.config = config;
        this.httpBackendPort = httpBackendPort;
        this.httpsBackendPort = httpsBackendPort;
        this.backendWait = backendWait;
        startIfTlsEnabled();
    }

    private void startIfTlsEnabled() {
        if (!config.tls().enabled()) {
            return;
        }

        client = vertx.createNetClient();
        Handler<NetSocket> connectHandler = buildConnectHandler();

        for (int port : listenPorts()) {
            NetServerOptions options = new NetServerOptions()
                    .setHost("0.0.0.0")
                    .setPort(port);
            NetServer server = vertx.createNetServer(options);
            server.connectHandler(connectHandler);
            proxyServers.add(server);
            server.listen().onComplete(ar -> {
                if (ar.succeeded()) {
                    LOG.infov("TLS proxy: listening on port {0} (HTTP→{1}, HTTPS→{2})",
                            String.valueOf(port), String.valueOf(httpBackendPort), String.valueOf(httpsBackendPort));
                } else if (port == config.port()) {
                    LOG.errorv("TLS proxy: failed to start on public port {0}: {1}",
                            String.valueOf(port), ar.cause().getMessage());
                } else {
                    // The extra HTTPS port (443 by default) is privileged; binding it fails in
                    // unprivileged environments (e.g. CI/test). Non-fatal: HTTPS on that port is
                    // simply unavailable. Set floci-oci.tls.https-port=0 to skip the attempt.
                    LOG.warnv("TLS proxy: could not bind HTTPS port {0} ({1}); HTTPS on {0} unavailable. "
                            + "Binding privileged ports needs elevated privileges; set floci-oci.tls.https-port=0 to disable.",
                            String.valueOf(port), ar.cause().getMessage());
                }
            });
        }
    }

    /**
     * The set of ports the proxy listens on: always the public {@link EmulatorConfig#port()},
     * plus {@code floci-oci.tls.https-port} (443 by default) for clients that assume OCI on 443.
     * Deduplicated (a coinciding https-port yields a single listener); a non-positive
     * https-port disables the extra binding.
     */
    Set<Integer> listenPorts() {
        Set<Integer> ports = new LinkedHashSet<>();
        ports.add(config.port());
        int httpsPort = config.tls().httpsPort();
        if (httpsPort > 0) {
            ports.add(httpsPort);
        }
        return ports;
    }

    /**
     * Builds the shared connect handler that peeks the first byte to detect TLS and pipes the
     * connection to the matching backend. A single instance is reused across all listen ports.
     */
    private Handler<NetSocket> buildConnectHandler() {
        return frontSocket -> {
            // Pause incoming data until we've peeked at the first byte
            frontSocket.pause();

            frontSocket.handler(buffer -> {
                // Remove handler and keep socket paused to prevent data loss
                // while we establish the backend connection.
                frontSocket.handler(null);
                frontSocket.pause();

                // Inspect first byte to determine protocol
                int backendPort;
                if (buffer.length() > 0 && buffer.getByte(0) == TLS_HANDSHAKE) {
                    backendPort = httpsBackendPort;
                } else {
                    backendPort = httpBackendPort;
                }

                connectBackend(frontSocket, buffer, backendPort, System.nanoTime() + backendWait.toNanos(),
                        new AtomicBoolean());
            });

            // Resume to receive the first buffer
            frontSocket.resume();
        };
    }

    /**
     * Connects to the backend and pipes the two sockets together. A refused connect is retried
     * until {@code deadline}, since a backend that is still starting refuses for a few hundred
     * milliseconds; the front socket stays paused meanwhile, and is closed only once the wait runs
     * out or the client has gone.
     */
    private void connectBackend(NetSocket frontSocket, Buffer firstBuffer, int backendPort, long deadline,
                                AtomicBoolean frontClosed) {
        frontSocket.closeHandler(ignored -> frontClosed.set(true));
        client.connect(backendPort, "127.0.0.1").onComplete(ar -> {
            if (ar.succeeded()) {
                NetSocket backSocket = ar.result();
                if (frontClosed.get()) {
                    backSocket.close();
                    return;
                }
                // Send the initial buffer that we already read
                backSocket.write(firstBuffer);

                // Bi-directional pipe: pipeTo handles end-of-stream
                // propagation and will resume the paused frontSocket.
                frontSocket.pipeTo(backSocket).onFailure(err ->
                        LOG.debugv("TLS proxy: pipe front→back failed: {0}", err.getMessage()));
                backSocket.pipeTo(frontSocket).onFailure(err ->
                        LOG.debugv("TLS proxy: pipe back→front failed: {0}", err.getMessage()));
            } else if (frontClosed.get()) {
                LOG.debugv("TLS proxy: client left while backend port {0} was starting", String.valueOf(backendPort));
            } else if (System.nanoTime() < deadline) {
                vertx.setTimer(BACKEND_RETRY_MILLIS,
                        id -> connectBackend(frontSocket, firstBuffer, backendPort, deadline, frontClosed));
            } else {
                LOG.warnv("TLS proxy: failed to connect to backend port {0} within {1} ms: {2}",
                        String.valueOf(backendPort), String.valueOf(backendWait.toMillis()), ar.cause().getMessage());
                frontSocket.close();
            }
        });
    }

    @PreDestroy
    void stop() {
        for (NetServer server : proxyServers) {
            server.close();
        }
        if (client != null) {
            client.close();
        }
        LOG.info("TLS proxy: stopped");
    }
}
