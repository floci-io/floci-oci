package sdktestgo

import (
	"context"
	"encoding/base64"
	"testing"

	"github.com/oracle/oci-go-sdk/v65/common"
	"github.com/oracle/oci-go-sdk/v65/keymanagement"
	"github.com/oracle/oci-go-sdk/v65/secrets"
	"github.com/oracle/oci-go-sdk/v65/vault"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func createVault(t *testing.T, name string) keymanagement.Vault {
	client, err := keymanagement.NewKmsVaultClientWithConfigurationProvider(configProvider(t))
	require.NoError(t, err)
	client.Host = getEndpoint()
	created, err := client.CreateVault(context.Background(), keymanagement.CreateVaultRequest{
		CreateVaultDetails: keymanagement.CreateVaultDetails{
			CompartmentId: common.String(tenancy()),
			DisplayName:   common.String(name),
			VaultType:     keymanagement.CreateVaultDetailsVaultTypeDefault,
		},
	})
	require.NoError(t, err)
	return created.Vault
}

// createAesKey builds the management client straight from the vault's returned endpoint, as
// the SDK requires.
func createAesKey(t *testing.T, vaultResource keymanagement.Vault, name string) keymanagement.Key {
	management, err := keymanagement.NewKmsManagementClientWithConfigurationProvider(configProvider(t),
		*vaultResource.ManagementEndpoint)
	require.NoError(t, err)
	created, err := management.CreateKey(context.Background(), keymanagement.CreateKeyRequest{
		CreateKeyDetails: keymanagement.CreateKeyDetails{
			CompartmentId: common.String(tenancy()),
			DisplayName:   common.String(name),
			KeyShape: &keymanagement.KeyShape{
				Algorithm: keymanagement.KeyShapeAlgorithmAes,
				Length:    common.Int(32),
			},
		},
	})
	require.NoError(t, err)
	return created.Key
}

func TestVaultAndKeyThroughReturnedEndpoints(t *testing.T) {
	vaultResource := createVault(t, unique("go-vault"))
	assert.Equal(t, "ACTIVE", string(vaultResource.LifecycleState))
	assert.NotEmpty(t, *vaultResource.ManagementEndpoint)
	assert.NotEmpty(t, *vaultResource.CryptoEndpoint)

	key := createAesKey(t, vaultResource, "go-key")
	// Keys reach ENABLED, not ACTIVE.
	assert.Equal(t, "ENABLED", string(key.LifecycleState))
	assert.Equal(t, *vaultResource.Id, *key.VaultId)
	assert.NotEmpty(t, *key.CurrentKeyVersion)
}

func TestKmsCryptoRoundtrip(t *testing.T) {
	vaultResource := createVault(t, unique("go-crypto-vault"))
	key := createAesKey(t, vaultResource, "go-crypto-key")

	crypto, err := keymanagement.NewKmsCryptoClientWithConfigurationProvider(configProvider(t),
		*vaultResource.CryptoEndpoint)
	require.NoError(t, err)
	ctx := context.Background()
	plaintext := base64.StdEncoding.EncodeToString([]byte("go crypto payload"))

	encrypted, err := crypto.Encrypt(ctx, keymanagement.EncryptRequest{
		EncryptDataDetails: keymanagement.EncryptDataDetails{KeyId: key.Id, Plaintext: common.String(plaintext)},
	})
	require.NoError(t, err)
	assert.NotEqual(t, plaintext, *encrypted.Ciphertext)

	decrypted, err := crypto.Decrypt(ctx, keymanagement.DecryptRequest{
		DecryptDataDetails: keymanagement.DecryptDataDetails{KeyId: key.Id, Ciphertext: encrypted.Ciphertext},
	})
	require.NoError(t, err)
	assert.Equal(t, plaintext, *decrypted.Plaintext)
	assert.NotEmpty(t, *decrypted.PlaintextChecksum)
}

func TestSecretIsWriteOnlyAndReadableViaBundle(t *testing.T) {
	vaultResource := createVault(t, unique("go-secret-vault"))
	key := createAesKey(t, vaultResource, "go-secret-key")
	ctx := context.Background()

	vaults, err := vault.NewVaultsClientWithConfigurationProvider(configProvider(t))
	require.NoError(t, err)
	vaults.Host = getEndpoint()
	secretValue := base64.StdEncoding.EncodeToString([]byte("go-s3cret"))

	created, err := vaults.CreateSecret(ctx, vault.CreateSecretRequest{
		CreateSecretDetails: vault.CreateSecretDetails{
			CompartmentId: common.String(tenancy()),
			VaultId:       vaultResource.Id,
			KeyId:         key.Id,
			SecretName:    common.String(unique("go-secret")),
			SecretContent: vault.Base64SecretContentDetails{Content: common.String(secretValue)},
		},
	})
	require.NoError(t, err)
	assert.Equal(t, "ACTIVE", string(created.LifecycleState))
	assert.Equal(t, int64(1), *created.CurrentVersionNumber)

	// Content only comes back through the separate secrets client.
	secretsClient, err := secrets.NewSecretsClientWithConfigurationProvider(configProvider(t))
	require.NoError(t, err)
	secretsClient.Host = getEndpoint()
	bundle, err := secretsClient.GetSecretBundle(ctx, secrets.GetSecretBundleRequest{SecretId: created.Id})
	require.NoError(t, err)
	assert.Equal(t, int64(1), *bundle.VersionNumber)
	content, ok := bundle.SecretBundleContent.(secrets.Base64SecretBundleContentDetails)
	require.Truef(t, ok, "expected BASE64 bundle content, got %T", bundle.SecretBundleContent)
	assert.Equal(t, secretValue, *content.Content)
}
