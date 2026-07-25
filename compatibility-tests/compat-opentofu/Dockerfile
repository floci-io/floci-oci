FROM debian:bookworm-slim

ARG OPENTOFU_VERSION=1.9.1
ARG BATS_VERSION=1.11.1

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates unzip openssl git \
    && rm -rf /var/lib/apt/lists/*

RUN arch="$(dpkg --print-architecture)" \
    && curl -fsSL "https://github.com/opentofu/opentofu/releases/download/v${OPENTOFU_VERSION}/tofu_${OPENTOFU_VERSION}_linux_${arch}.zip" -o /tmp/tofu.zip \
    && unzip -q /tmp/tofu.zip -d /usr/local/bin tofu && rm /tmp/tofu.zip

RUN git clone --depth 1 --branch "v${BATS_VERSION}" https://github.com/bats-core/bats-core.git /tmp/bats \
    && /tmp/bats/install.sh /usr/local && rm -rf /tmp/bats

WORKDIR /suite
COPY provider.tf main.tf ./
COPY test/ test/
COPY run-bats-in-container.sh .

# Pre-download the provider at build time so the run is fast and network-independent.
RUN openssl genrsa -out test_key.pem 2048 && tofu init -input=false

ENV TF_BIN=tofu
ENTRYPOINT ["sh", "run-bats-in-container.sh"]
