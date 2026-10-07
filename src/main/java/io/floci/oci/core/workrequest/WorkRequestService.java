package io.floci.oci.core.workrequest;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared work-request plane: OCI's async-operation record store. Services create a
 * work request when returning {@code 202} + {@code opc-work-request-id}; clients and
 * Terraform poll it until terminal. Most emulated operations complete synchronously, so the
 * typical call is {@link #succeeded}, and the record carries populated {@code resources}
 * and a {@code timeFinished}. Terraform's retry predicates spin until both are present,
 * which is what an operation that really runs in the background ({@link #inProgress},
 * then {@link #finish}) relies on.
 *
 * <p>Work requests are partitioned by owning service ({@link StoredWorkRequest#getService()})
 * because each OCI service exposes its own {@code /workRequests} listing; a queue work
 * request must not appear in Identity's list.
 *
 * <p>The terminal success status differs per service on real OCI: Identity, Queue and
 * Streaming use {@code SUCCEEDED}; Object Storage uses {@code COMPLETED}.
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
     * Records an already-completed operation with terminal status {@code SUCCEEDED} and
     * returns its work-request OCID, the value for the {@code opc-work-request-id} header.
     */
    public String succeeded(String service, String operationType, String compartmentId,
                            List<StoredWorkRequest.Resource> resources) {
        return completed(service, operationType, compartmentId, resources, "SUCCEEDED");
    }

    /** Records a completed operation with a service-specific terminal status. */
    public String completed(String service, String operationType, String compartmentId,
                            List<StoredWorkRequest.Resource> resources, String terminalStatus) {
        StoredWorkRequest wr = base(service, operationType, compartmentId, resources);
        wr.setStatus(terminalStatus);
        wr.setPercentComplete(100.0f);
        String now = Instant.now().toString();
        wr.setTimeStarted(now);
        wr.setTimeFinished(now);
        store.put(wr.getId(), wr);
        LOG.debugf("workRequest %s: %s %s (%s)", wr.getId(), operationType, terminalStatus, service);
        return wr.getId();
    }

    /**
     * Records an operation that is still running: {@code IN_PROGRESS}, 0%, started, no
     * {@code timeFinished}. The resources must already be populated: Terraform reads them once
     * right after the create to learn the new resource's identifier. Close it with
     * {@link #finish}.
     */
    public String inProgress(String service, String operationType, String compartmentId,
                             List<StoredWorkRequest.Resource> resources) {
        StoredWorkRequest wr = base(service, operationType, compartmentId, resources);
        wr.setStatus("IN_PROGRESS");
        wr.setPercentComplete(0.0f);
        wr.setTimeStarted(Instant.now().toString());
        store.put(wr.getId(), wr);
        LOG.debugv("workRequest {0}: {1} IN_PROGRESS ({2})", wr.getId(), operationType, service);
        return wr.getId();
    }

    /**
     * Moves an in-progress work request to {@code terminalStatus}. Takes the owning tenancy
     * explicitly because async workers run outside any request scope. A work request that is
     * missing or already finished is left untouched.
     */
    public void finish(String tenancyId, String workRequestId, String terminalStatus) {
        Optional<StoredWorkRequest> found = store instanceof TenancyAwareStorageBackend<StoredWorkRequest> tenancyAware
                ? tenancyAware.getForTenancy(tenancyId, workRequestId)
                : store.get(workRequestId);
        if (found.isEmpty()) {
            LOG.warnv("workRequest {0} not found in tenancy {1}; cannot mark it {2}",
                    workRequestId, tenancyId, terminalStatus);
            return;
        }
        StoredWorkRequest wr = found.get();
        if (wr.getTimeFinished() != null) {
            return;
        }
        wr.setStatus(terminalStatus);
        if ("SUCCEEDED".equals(terminalStatus)) {
            wr.setPercentComplete(100.0f);
        }
        wr.setTimeFinished(Instant.now().toString());
        if (store instanceof TenancyAwareStorageBackend<StoredWorkRequest> tenancyAware) {
            tenancyAware.putForTenancy(tenancyId, workRequestId, wr);
        } else {
            store.put(workRequestId, wr);
        }
        LOG.debugv("workRequest {0}: {1} ({2})", workRequestId, terminalStatus, wr.getService());
    }

    /** Records a failed operation and returns its work-request OCID. */
    public String failed(String service, String operationType, String compartmentId,
                         List<StoredWorkRequest.Resource> resources) {
        StoredWorkRequest wr = base(service, operationType, compartmentId, resources);
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

    /** Get scoped to a service: foreign-service work requests read as not found. */
    public StoredWorkRequest get(String service, String workRequestId) {
        StoredWorkRequest wr = get(workRequestId);
        if (service != null && wr.getService() != null && !service.equals(wr.getService())) {
            throw OciException.notAuthorizedOrNotFound(
                    "Work request not found or not authorized: " + workRequestId);
        }
        return wr;
    }

    public List<StoredWorkRequest> list(String service, String compartmentId) {
        return store.scan(k -> true).stream()
                .filter(wr -> service == null || service.equals(wr.getService()))
                .filter(wr -> compartmentId == null || compartmentId.equals(wr.getCompartmentId()))
                .toList();
    }

    public static StoredWorkRequest.Resource resource(String entityType, String actionType,
                                                      String identifier, String entityUri) {
        return new StoredWorkRequest.Resource(entityType, actionType, identifier, entityUri);
    }

    private StoredWorkRequest base(String service, String operationType, String compartmentId,
                                   List<StoredWorkRequest.Resource> resources) {
        StoredWorkRequest wr = new StoredWorkRequest();
        wr.setId(Ocids.generateGlobal("coreservicesworkrequest", config.defaultRealm()));
        wr.setService(service);
        wr.setOperationType(operationType);
        wr.setCompartmentId(compartmentId);
        wr.setTimeAccepted(Instant.now().toString());
        wr.setResources(resources == null ? List.of() : resources);
        return wr;
    }
}
