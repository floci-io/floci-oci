FROM debian:bookworm-slim

ARG TERRAFORM_VERSION=1.10.5
ARG BATS_VERSION=1.11.1

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates unzip openssl git \
    && rm -rf /var/lib/apt/lists/*

RUN arch="$(dpkg --print-architecture)" \
    && curl -fsSL "https://releases.hashicorp.com/terraform/${TERRAFORM_VERSION}/terraform_${TERRAFORM_VERSION}_linux_${arch}.zip" -o /tmp/tf.zip \
    && unzip -q /tmp/tf.zip -d /usr/local/bin && rm /tmp/tf.zip

RUN git clone --depth 1 --branch "v${BATS_VERSION}" https://github.com/bats-core/bats-core.git /tmp/bats \
    && /tmp/bats/install.sh /usr/local && rm -rf /tmp/bats

WORKDIR /suite
COPY provider.tf main.tf ./
COPY test/ test/
COPY run-bats-in-container.sh .

# Pre-download the provider at build time so the run is fast and network-independent.
RUN openssl genrsa -out test_key.pem 2048 && terraform init -input=false

ENV TF_BIN=terraform
ENTRYPOINT ["sh", "run-bats-in-container.sh"]
