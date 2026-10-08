package io.floci.oci.core.workrequest;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
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

    private static final String GLOBAL_SERVICE = "identity";

    private final StorageBackend<String, StoredWorkRequest> store;
    private final StorageBackend<String, StoredWorkRequest> globalStore;
    private final OciContext ociContext;

    @Inject
    public WorkRequestService(StorageFactory storageFactory, OciContext ociContext) {
        this.ociContext = ociContext;
        this.store = storageFactory.create("workrequests", "workrequests.json",
                new TypeReference<Map<String, StoredWorkRequest>>() {});
        this.globalStore = storageFactory.createGlobal("workrequests",
                "identity-workrequests.json",
                new TypeReference<Map<String, StoredWorkRequest>>() {});
        storageFactory.afterLoad(this::moveIdentityWorkRequestsToGlobalStore);
    }

    /** Storage-injecting constructor for tests. */
    public WorkRequestService(StorageBackend<String, StoredWorkRequest> store, EmulatorConfig config) {
        this(store, store, config);
    }

    WorkRequestService(StorageBackend<String, StoredWorkRequest> store,
                       StorageBackend<String, StoredWorkRequest> globalStore, EmulatorConfig config) {
        this.store = store;
        this.globalStore = globalStore;
        this.ociContext = OciContext.fromConfig(config);
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
        storeFor(service).put(wr.getId(), wr);
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
     * Moves an in-progress work request to {@code terminalStatus}. Takes the owning tenancy and
     * region explicitly because async workers run outside any request scope; work-request OCIDs
     * are global, so the region comes from the resource the work request is about. A work
     * request that is missing or already finished is left untouched.
     */
    public void finish(String tenancyId, String region, String workRequestId, String terminalStatus) {
        String partition = TenancyAwareStorageBackend.regionalPartition(tenancyId, region);
        Optional<StoredWorkRequest> found = store instanceof TenancyAwareStorageBackend<StoredWorkRequest> tenancyAware
                ? tenancyAware.getForTenancy(partition, workRequestId)
                : store.get(workRequestId);
        if (found.isEmpty()) {
            LOG.warnv("workRequest {0} not found in tenancy {1}, region {2}; cannot mark it {3}",
                    workRequestId, tenancyId, region, terminalStatus);
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
            tenancyAware.putForTenancy(partition, workRequestId, wr);
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
        storeFor(service).put(wr.getId(), wr);
        return wr.getId();
    }

    public StoredWorkRequest get(String workRequestId) {
        return store.get(workRequestId)
                .or(() -> globalStore.get(workRequestId))
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
        return storeFor(service).scan(k -> true).stream()
                .filter(wr -> service == null || service.equals(wr.getService()))
                .filter(wr -> compartmentId == null || compartmentId.equals(wr.getCompartmentId()))
                .toList();
    }

    /**
     * Identity work requests written before Identity became global sit in the regional store;
     * moves them into the global one, keeping their tenancy.
     */
    void moveIdentityWorkRequestsToGlobalStore() {
        if (!(store instanceof TenancyAwareStorageBackend<StoredWorkRequest> regional)
                || !(globalStore instanceof TenancyAwareStorageBackend<StoredWorkRequest> global)
                || store == globalStore) {
            return;
        }
        int moved = 0;
        for (String partition : regional.tenancies()) {
            for (String key : regional.keysForTenancy(partition)) {
                Optional<StoredWorkRequest> wr = regional.getForTenancy(partition, key);
                if (wr.isPresent() && GLOBAL_SERVICE.equals(wr.get().getService())) {
                    global.putForTenancy(TenancyAwareStorageBackend.tenancyOf(partition), key, wr.get());
                    regional.deleteForTenancy(partition, key);
                    moved++;
                }
            }
        }
        if (moved > 0) {
            global.flush();
            regional.flush();
            LOG.infov("Moved {0} Identity work requests into the global store", moved);
        }
    }

    /** Identity is global, so its work requests are too; every other service's are regional. */
    private StorageBackend<String, StoredWorkRequest> storeFor(String service) {
        return GLOBAL_SERVICE.equals(service) ? globalStore : store;
    }

    public static StoredWorkRequest.Resource resource(String entityType, String actionType,
                                                      String identifier, String entityUri) {
        return new StoredWorkRequest.Resource(entityType, actionType, identifier, entityUri);
    }

    private StoredWorkRequest base(String service, String operationType, String compartmentId,
                                   List<StoredWorkRequest.Resource> resources) {
        StoredWorkRequest wr = new StoredWorkRequest();
        wr.setId(Ocids.generateGlobal("coreservicesworkrequest", ociContext.realm()));
        wr.setService(service);
        wr.setOperationType(operationType);
        wr.setCompartmentId(compartmentId);
        wr.setTimeAccepted(Instant.now().toString());
        wr.setResources(resources == null ? List.of() : resources);
        return wr;
    }
}
