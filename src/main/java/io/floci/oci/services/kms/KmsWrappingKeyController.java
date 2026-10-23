package io.floci.oci.services.kms;

import io.floci.oci.services.kms.model.StoredVault;
import io.floci.oci.services.kms.model.StoredWrappingKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KMS {@code GetWrappingKey}, served on the vault's {@code managementEndpoint}. The emulator
 * holds one wrapping key for every vault; see {@code KmsService#wrappingKey}.
 */
@Path("/20180608/wrappingKeys")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class KmsWrappingKeyController {

    private final KmsService service;

    @Inject
    public KmsWrappingKeyController(KmsService service) {
        this.service = service;
    }

    @GET
    public Response getWrappingKey() {
        StoredVault vault = service.wrappingKeyVault();
        StoredWrappingKey wrappingKey = service.wrappingKey();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("compartmentId", vault.getCompartmentId());
        json.put("id", wrappingKey.getId());
        json.put("lifecycleState", "ENABLED");
        json.put("publicKey", wrappingKey.getPublicKeyPem());
        json.put("timeCreated", wrappingKey.getTimeCreated());
        json.put("vaultId", vault.getId());
        return Response.ok(json).header("etag", wrappingKey.getEtag()).build();
    }
}
