package io.floci.oci.core.workrequest;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Shared work-request plane — OCI's async-operation record store. Services create a
 * work request when returning {@code 202} + {@code opc-work-request-id}; clients and
 * Terraform poll it until {@code SUCCEEDED}. Emulated operations complete synchronously,
 * so the typical call is {@link #succeeded}.
 */
@ApplicationScoped
public class WorkRequestService {

    private static final Logger LOG = Logger.getLogger(WorkRequestService.class);

    private final StorageBackend<String, StoredWorkRequest> store;
    private final EmulatorConfig config;

    @Inject
    public WorkRequestService(StorageFactory storageFactory, EmulatorConfig config) {
        this.config = config;
        this.store = storageFactory.create("workrequests", "workrequests.json",
                new TypeReference<Map<String, StoredWorkRequest>>() {});
    }

    /** Storage-injecting constructor for tests. */
    public WorkRequestService(StorageBackend<String, StoredWorkRequest> store, EmulatorConfig config) {
        this.store = store;
        this.config = config;
    }

    /**
     * Records an already-completed operation and returns its work-request OCID —
     * the value for the {@code opc-work-request-id} response header.
     *
     * <p>The terminal success status differs per service on real OCI: Identity uses
     * {@code SUCCEEDED}, Object Storage uses {@code COMPLETED}.
     */
    public String succeeded(String operationType, String compartmentId,
                            List<StoredWorkRequest.Resource> resources) {
        return completed(operationType, compartmentId, resources, "SUCCEEDED");
    }

    /** Records a completed operation with a service-specific terminal status. */
    public String completed(String operationType, String compartmentId,
                            List<StoredWorkRequest.Resource> resources, String terminalStatus) {
        StoredWorkRequest wr = base(operationType, compartmentId, resources);
        wr.setStatus(terminalStatus);
        wr.setPercentComplete(100.0f);
        String now = Instant.now().toString();
        wr.setTimeStarted(now);
        wr.setTimeFinished(now);
        store.put(wr.getId(), wr);
        LOG.debugf("workRequest %s: %s SUCCEEDED", wr.getId(), operationType);
        return wr.getId();
    }

    /** Records a failed operation and returns its work-request OCID. */
    public String failed(String operationType, String compartmentId,
                         List<StoredWorkRequest.Resource> resources) {
        StoredWorkRequest wr = base(operationType, compartmentId, resources);
        wr.setStatus("FAILED");
        wr.setPercentComplete(0.0f);
        String now = Instant.now().toString();
        wr.setTimeStarted(now);
        wr.setTimeFinished(now);
        store.put(wr.getId(), wr);
        return wr.getId();
    }

    public StoredWorkRequest get(String workRequestId) {
        return store.get(workRequestId)
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound(
                        "Work request not found or not authorized: " + workRequestId));
    }

    public List<StoredWorkRequest> listByCompartment(String compartmentId) {
        return store.scan(k -> true).stream()
                .filter(wr -> compartmentId == null || compartmentId.equals(wr.getCompartmentId()))
                .toList();
    }

    public static StoredWorkRequest.Resource resource(String entityType, String actionType,
                                                      String identifier, String entityUri) {
        return new StoredWorkRequest.Resource(entityType, actionType, identifier, entityUri);
    }

    private StoredWorkRequest base(String operationType, String compartmentId,
                                   List<StoredWorkRequest.Resource> resources) {
        StoredWorkRequest wr = new StoredWorkRequest();
        wr.setId(Ocids.generateGlobal("coreservicesworkrequest", config.defaultRealm()));
        wr.setOperationType(operationType);
        wr.setCompartmentId(compartmentId);
        wr.setTimeAccepted(Instant.now().toString());
        wr.setResources(resources == null ? List.of() : resources);
        return wr;
    }
}
