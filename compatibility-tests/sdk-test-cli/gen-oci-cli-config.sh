#!/bin/sh
# Shell port of docker/gen-oci-cli-config.py for the CLI compat suite.
#
# The OCI CLI signs every request and refuses to run without an API key, while
# floci-oci parses the signature but never verifies it (see OciSignatureParser).
# This key exists only to get the CLI past its own validation — it is public by
# construction; never point this config at real OCI.
#
# The tenancy must match floci-oci.default-tenancy-id: tenancy is the storage
# partition, so a mismatch would put CLI writes in a different partition.
set -eu

DIR="${1:?usage: gen-oci-cli-config.sh <output-dir>}"
TENANCY="ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000"
USER_ID="ocid1.user.oc1..flocilocaluser00000000000000000000000000000000000000000000"
REGION="us-ashburn-1"

mkdir -p "$DIR"
openssl genrsa -out "$DIR/key.pem" 2048 2>/dev/null

# OCI fingerprints are the colon-separated MD5 of the DER public key.
FINGERPRINT=$(openssl rsa -in "$DIR/key.pem" -pubout -outform DER 2>/dev/null \
    | openssl md5 -c | awk '{print $NF}')

# The CLI warns on every invocation unless the key carries this trailing label.
printf 'OCI_API_KEY\n' >> "$DIR/key.pem"

cat > "$DIR/config" <<EOF
[DEFAULT]
user=$USER_ID
fingerprint=$FINGERPRINT
key_file=$DIR/key.pem
tenancy=$TENANCY
region=$REGION
EOF

chmod 600 "$DIR/key.pem" "$DIR/config"
echo "wrote $DIR/config (fingerprint $FINGERPRINT)"
