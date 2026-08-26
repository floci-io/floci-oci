FROM python:3.12-slim

# Pinned to the version of the local/oracle/oci-cli reference clone; bump the
# pin whenever `make refs` fast-forwards it.
ARG OCI_CLI_VERSION=3.89.3
ARG BATS_VERSION=1.11.1

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates jq openssl git \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir "oci-cli==${OCI_CLI_VERSION}"

RUN git clone --depth 1 --branch "v${BATS_VERSION}" https://github.com/bats-core/bats-core.git /tmp/bats \
    && /tmp/bats/install.sh /usr/local && rm -rf /tmp/bats

WORKDIR /suite
COPY gen-oci-cli-config.sh run-bats-in-container.sh ./
COPY test/ test/

# Pre-generate the throwaway API key + config at build time so runs start fast.
RUN sh gen-oci-cli-config.sh /suite/.oci
ENV OCI_CLI_CONFIG_FILE=/suite/.oci/config

ENTRYPOINT ["sh", "run-bats-in-container.sh"]
