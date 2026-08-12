package org.floci.core.server;

/**
 * Configuration holder for HTTP server ports, SSL settings, and thread pools.
 */
public class ServerConfig {
    private final String host;
    private final int port;
    private final boolean sslEnabled;
    private final int workerThreads;

    private ServerConfig(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.sslEnabled = builder.sslEnabled;
        this.workerThreads = builder.workerThreads;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ServerConfig defaultConfig() {
        return builder().build();
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isSslEnabled() {
        return sslEnabled;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public static class Builder {
        private String host = "0.0.0.0";
        private int port = 8080;
        private boolean sslEnabled = false;
        private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;

        public Builder host(String host) {
            if (host != null && !host.isBlank()) {
                this.host = host;
            }
            return this;
        }

        public Builder port(int port) {
            if (port > 0 && port <= 65535) {
                this.port = port;
            }
            return this;
        }

        public Builder sslEnabled(boolean sslEnabled) {
            this.sslEnabled = sslEnabled;
            return this;
        }

        public Builder workerThreads(int workerThreads) {
            if (workerThreads > 0) {
                this.workerThreads = workerThreads;
            }
            return this;
        }

        public ServerConfig build() {
            return new ServerConfig(this);
        }
    }
}
