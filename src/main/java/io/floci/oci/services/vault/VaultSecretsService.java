package io.floci.oci.services.vault;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.services.vault.model.StoredVaultSecret;
import io.floci.oci.services.vault.model.StoredVaultSecret.StoredSecretVersion;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Vault secrets: management plane (VaultsClient, {@code /20180608/secrets…}) and the
 * retrieval plane (SecretsClient, {@code /20190301/secretbundles…}). The {@code Secret}
 * wire shape never carries content — content is only retrievable through bundles.
 */
@ApplicationScoped
public class VaultSecretsService {

    private static final Logger LOG = Logger.getLogger(VaultSecretsService.class);

    private final StorageBackend<String, StoredVaultSecret> secrets;
    private final EmulatorConfig config;
    private final ServiceRegistry serviceRegistry;

    @Inject
    public VaultSecretsService(StorageFactory storageFactory, EmulatorConfig config,
                               ServiceRegistry serviceRegistry) {
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.secrets = storageFactory.create("vault", "vault-secrets.json",
                new TypeReference<Map<String, StoredVaultSecret>>() {});
    }

    VaultSecretsService(StorageBackend<String, StoredVaultSecret> secrets, EmulatorConfig config) {
        this.secrets = secrets;
        this.config = config;
        this.serviceRegistry = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("vault")
                .enabled(config.services().vault().enabled())
                .storageKey("vault")
                .resourceClasses(VaultSecretsController.class, SecretBundleController.class)
                .build());
    }

    // ── Management plane ───────────────────────────────────────────────────────

    public StoredVaultSecret createSecret(String compartmentId, String vaultId, String keyId,
                                          String secretName, String description,
                                          Map<String, Object> secretContent,
                                          Map<String, Object> metadata,
                                          Map<String, String> freeformTags,
                                          Map<String, Map<String, Object>> definedTags) {
        require(compartmentId, "compartmentId");
        require(vaultId, "vaultId");
        require(keyId, "keyId");
        require(secretName, "secretName");
        boolean duplicate = secrets.scan(k -> true).stream()
                .anyMatch(s -> vaultId.equals(s.getVaultId()) && secretName.equals(s.getSecretName())
                        && "ACTIVE".equals(s.getLifecycleState()));
        if (duplicate) {
            throw OciException.conflict("Secret " + secretName + " already exists in vault " + vaultId);
        }
        StoredVaultSecret secret = new StoredVaultSecret();
        secret.setId(Ocids.generate("vaultsecret", config.defaultRealm(), regionShort()));
        secret.setCompartmentId(compartmentId);
        secret.setVaultId(vaultId);
        secret.setKeyId(keyId);
        secret.setSecretName(secretName);
        secret.setDescription(description);
        secret.setLifecycleState("ACTIVE");
        secret.setTimeCreated(Instant.now().toString());
        secret.setMetadata(metadata);
        secret.setFreeformTags(freeformTags);
        secret.setDefinedTags(definedTags);
        secret.setEtag(Etags.newEtag());
        if (secretContent != null) {
            addVersion(secret, secretContent);
        }
        secrets.put(secret.getId(), secret);
        LOG.infof("createSecret %s (%s)", secretName, secret.getId());
        return secret;
    }

    public StoredVaultSecret getSecret(String secretId) {
        return secrets.get(secretId).orElseThrow(() -> notFound(secretId));
    }

    public List<StoredVaultSecret> listSecrets(String compartmentId, String vaultId, String name) {
        require(compartmentId, "compartmentId");
        return secrets.scan(k -> true).stream()
                .filter(s -> compartmentId.equals(s.getCompartmentId()))
                .filter(s -> vaultId == null || vaultId.equals(s.getVaultId()))
                .filter(s -> name == null || name.equals(s.getSecretName()))
                .sorted(Comparator.comparing(StoredVaultSecret::getTimeCreated))
                .toList();
    }

    public StoredVaultSecret updateSecret(String secretId, String description,
                                          Map<String, Object> secretContent,
                                          Map<String, Object> metadata,
                                          Map<String, String> freeformTags,
                                          Map<String, Map<String, Object>> definedTags,
                                          String ifMatch) {
        StoredVaultSecret secret = getSecret(secretId);
        Etags.checkIfMatch(ifMatch, secret.getEtag());
        if (description != null) {
            secret.setDescription(description);
        }
        if (metadata != null) {
            secret.setMetadata(metadata);
        }
        if (freeformTags != null) {
            secret.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            secret.setDefinedTags(definedTags);
        }
        if (secretContent != null) {
            addVersion(secret, secretContent);
        }
        secret.setEtag(Etags.newEtag());
        secrets.put(secretId, secret);
        return secret;
    }

    /** ScheduleSecretDeletion returns no body on the wire — only etag + opc-request-id. */
    public StoredVaultSecret scheduleSecretDeletion(String secretId, String timeOfDeletion,
                                                    String ifMatch) {
        StoredVaultSecret secret = getSecret(secretId);
        Etags.checkIfMatch(ifMatch, secret.getEtag());
        secret.setLifecycleState("PENDING_DELETION");
        secret.setTimeOfDeletion(timeOfDeletion != null ? timeOfDeletion
                : Instant.now().plusSeconds(30 * 24 * 3600).toString());
        secret.setEtag(Etags.newEtag());
        secrets.put(secretId, secret);
        return secret;
    }

    public StoredVaultSecret cancelSecretDeletion(String secretId, String ifMatch) {
        StoredVaultSecret secret = getSecret(secretId);
        Etags.checkIfMatch(ifMatch, secret.getEtag());
        secret.setLifecycleState("ACTIVE");
        secret.setTimeOfDeletion(null);
        secret.setEtag(Etags.newEtag());
        secrets.put(secretId, secret);
        return secret;
    }

    public void changeSecretCompartment(String secretId, String compartmentId, String ifMatch) {
        StoredVaultSecret secret = getSecret(secretId);
        Etags.checkIfMatch(ifMatch, secret.getEtag());
        require(compartmentId, "compartmentId");
        secret.setCompartmentId(compartmentId);
        secret.setEtag(Etags.newEtag());
        secrets.put(secretId, secret);
    }

    public StoredSecretVersion getSecretVersion(String secretId, long versionNumber) {
        return getSecret(secretId).getVersions().stream()
                .filter(v -> v.getVersionNumber() == versionNumber)
                .findFirst()
                .orElseThrow(() -> notFound(secretId + " version " + versionNumber));
    }

    public List<StoredSecretVersion> listSecretVersions(String secretId) {
        return List.copyOf(getSecret(secretId).getVersions());
    }

    // ── Retrieval plane (secret bundles) ───────────────────────────────────────

    public record Bundle(StoredVaultSecret secret, StoredSecretVersion version) {
    }

    public Bundle getBundle(String secretId, Long versionNumber, String stage) {
        StoredVaultSecret secret = getSecret(secretId);
        if ("PENDING_DELETION".equals(secret.getLifecycleState())) {
            throw notFound(secretId);
        }
        StoredSecretVersion version = resolveVersion(secret, versionNumber, stage);
        return new Bundle(secret, version);
    }

    public Bundle getBundleByName(String secretName, String vaultId, Long versionNumber, String stage) {
        require(secretName, "secretName");
        require(vaultId, "vaultId");
        StoredVaultSecret secret = secrets.scan(k -> true).stream()
                .filter(s -> vaultId.equals(s.getVaultId()) && secretName.equals(s.getSecretName()))
                .filter(s -> !"PENDING_DELETION".equals(s.getLifecycleState()))
                .findFirst()
                .orElseThrow(() -> notFound(secretName));
        return new Bundle(secret, resolveVersion(secret, versionNumber, stage));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void addVersion(StoredVaultSecret secret, Map<String, Object> secretContent) {
        String contentType = secretContent.get("contentType") instanceof String s ? s : null;
        if (!"BASE64".equals(contentType)) {
            throw OciException.invalidParameter("secretContent.contentType must be BASE64");
        }
        String content = secretContent.get("content") instanceof String s ? s : null;
        StoredSecretVersion version = new StoredSecretVersion();
        long number = secret.getCurrentVersionNumber() + 1;
        version.setVersionNumber(number);
        version.setName(secretContent.get("name") instanceof String s ? s : null);
        version.setContentType("BASE64");
        version.setContent(content);
        version.setTimeCreated(Instant.now().toString());
        version.setStages(new ArrayList<>(List.of("CURRENT", "LATEST")));
        for (StoredSecretVersion previous : secret.getVersions()) {
            previous.getStages().remove("CURRENT");
            previous.getStages().remove("LATEST");
            previous.getStages().remove("PREVIOUS");
            if (previous.getVersionNumber() == number - 1) {
                previous.getStages().add("PREVIOUS");
            }
        }
        secret.getVersions().add(version);
        secret.setCurrentVersionNumber(number);
    }

    private StoredSecretVersion resolveVersion(StoredVaultSecret secret, Long versionNumber,
                                               String stage) {
        if (secret.getVersions().isEmpty()) {
            throw notFound(secret.getId());
        }
        if (versionNumber != null) {
            return secret.getVersions().stream()
                    .filter(v -> v.getVersionNumber() == versionNumber)
                    .findFirst()
                    .orElseThrow(() -> notFound(secret.getId() + " version " + versionNumber));
        }
        String effectiveStage = stage != null ? stage : "CURRENT";
        return secret.getVersions().stream()
                .filter(v -> v.getStages().contains(effectiveStage))
                .max(Comparator.comparingLong(StoredSecretVersion::getVersionNumber))
                .orElseThrow(() -> notFound(secret.getId() + " stage " + effectiveStage));
    }

    String regionShort() {
        return Ocids.regionShort(config.defaultRegion());
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: " + field);
        }
    }

    private static OciException notFound(String what) {
        return OciException.notAuthorizedOrNotFound(
                "Authorization failed or requested resource not found: " + what);
    }
}
