FROM golang:1.26-bookworm

# JUnit XML for the CI contract (/results), like the other suites write.
ARG GOTESTSUM_VERSION=1.13.0
RUN go install "gotest.tools/gotestsum@v${GOTESTSUM_VERSION}"

WORKDIR /suite

# go.mod pins oci-go-sdk to the version of the local/oracle/oci-go-sdk reference clone;
# bump it whenever `make refs` fast-forwards it. go.sum is gitignored repo-wide, so it is
# written here, every module checked against the Go checksum database. Dependencies come
# first, keyed on go.mod alone, so a source-only change reuses the download layer.
COPY go.mod ./
RUN go mod download
COPY *.go ./
RUN go mod tidy && go test -c -o /dev/null .

COPY run-go-in-container.sh ./

ENV FLOCI_OCI_ENDPOINT=http://floci-oci:4599

ENTRYPOINT ["sh", "run-go-in-container.sh"]
