package sdktestgo

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/oracle/oci-go-sdk/v65/common"
	"github.com/stretchr/testify/require"
)

const fingerprint = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"

var (
	keyOnce sync.Once
	keyPEM  string
	keyErr  error
)

func getEndpoint() string {
	return envOr("FLOCI_OCI_ENDPOINT", "http://localhost:4599")
}

// tenancy is the storage partition every test writes into, and also the root compartment.
func tenancy() string {
	return envOr("FLOCI_OCI_TENANCY", "ocid1.tenancy.oc1..flocilocalgocompattenancy000000000000000000000000000000")
}

func envOr(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

// throwawayKey returns a PEM RSA key, generated once per run: the SDK signs every request, so
// the key must parse, but the emulator never verifies the signature.
func throwawayKey(t *testing.T) string {
	keyOnce.Do(func() {
		key, err := rsa.GenerateKey(rand.Reader, 2048)
		if err != nil {
			keyErr = err
			return
		}
		keyPEM = string(pem.EncodeToMemory(&pem.Block{
			Type:  "RSA PRIVATE KEY",
			Bytes: x509.MarshalPKCS1PrivateKey(key),
		}))
	})
	require.NoError(t, keyErr)
	return keyPEM
}

func configProvider(t *testing.T) common.ConfigurationProvider {
	return common.NewRawConfigurationProvider(tenancy(), "ocid1.user.oc1..gocompatuser", "us-ashburn-1",
		fingerprint, throwawayKey(t), nil)
}

// noRetry is for a request whose 404 is expected or harmless: a read after a delete, or a
// cleanup delete. The SDK's default policy treats 404 NotAuthorizedOrNotFound after a delete as
// eventual consistency and retries it for about four minutes (common/eventual_consistency.go).
func noRetry() common.RequestMetadata {
	policy := common.NoRetryPolicy()
	return common.RequestMetadata{RetryPolicy: &policy}
}

func unique(prefix string) string {
	return fmt.Sprintf("%s-%d", prefix, time.Now().UnixNano())
}

// requireServiceError asserts err is an OCI service error with the given status and code.
func requireServiceError(t *testing.T, err error, status int, code string) {
	t.Helper()
	require.Error(t, err)
	serviceErr, ok := common.IsServiceError(err)
	require.Truef(t, ok, "expected an OCI service error, got %v", err)
	require.Equal(t, status, serviceErr.GetHTTPStatusCode())
	require.Equal(t, code, serviceErr.GetCode())
	require.NotEmpty(t, serviceErr.GetOpcRequestID(), "every error carries opc-request-id")
}
