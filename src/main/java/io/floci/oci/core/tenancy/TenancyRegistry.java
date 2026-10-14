package io.floci.oci.core.tenancy;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The tenancies the emulator knows and their home regions. A signed tenancy seen for the first
 * time is registered with {@code default-region} as its home, and the record pins it: changing
 * {@code default-region} later does not move an existing tenancy's home. Organizations registers
 * child tenancies here with the home region the request names.
 *
 * <p>Each record lives in its own tenancy's partition of a global (region-independent) store,
 * keyed by the tenancy OCID.
 */
@ApplicationScoped
public class TenancyRegistry {

    private static final Logger LOG = Logger.getLogger(TenancyRegistry.class);

    static final String DEFAULT_NAME = "floci-local";
    static final String DEFAULT_DESCRIPTION = "floci-oci emulated tenancy";

    private final StorageBackend<String, StoredTenancy> tenancies;
    private final EmulatorConfig config;
    private final OciContext ociContext;

    @Inject
    public TenancyRegistry(StorageFactory storageFactory, EmulatorConfig config, OciContext ociContext) {
        this(storageFactory.createGlobal("identity", "identity-tenancies.json",
                new TypeReference<Map<String, StoredTenancy>>() {}), config, ociContext);
    }

    /** Storage-injecting constructor for tests. */
    public TenancyRegistry(StorageBackend<String, StoredTenancy> tenancies, EmulatorConfig config,
                           OciContext ociContext) {
        this.tenancies = tenancies;
        this.config = config;
        this.ociContext = ociContext;
    }

    /** The tenancy of the current request, registering it on first sight. */
    public StoredTenancy current() {
        return get(ociContext.tenancyId());
    }

    /** The tenancy {@code tenancyId}, registering it with the default home region on first sight. */
    public StoredTenancy get(String tenancyId) {
        Optional<StoredTenancy> existing = find(tenancyId);
        if (existing.isPresent()) {
            return existing.get();
        }
        return register(tenancyId);
    }

    private synchronized StoredTenancy register(String tenancyId) {
        Optional<StoredTenancy> existing = find(tenancyId);
        if (existing.isPresent()) {
            return existing.get();
        }
        StoredTenancy tenancy = newTenancy(tenancyId, DEFAULT_NAME, config.defaultRegion(), null);
        tenancy.setDescription(DEFAULT_DESCRIPTION);
        save(tenancy);
        LOG.debugv("Registered tenancy {0} with home region {1}", tenancyId, tenancy.getHomeRegion());
        return tenancy;
    }

    /** The tenancy {@code tenancyId} if it is registered, without registering it. */
    public Optional<StoredTenancy> find(String tenancyId) {
        return TenancyAwareStorageBackend.getIn(tenancies, tenancyId, tenancyId);
    }

    public String homeRegion(String tenancyId) {
        return get(tenancyId).getHomeRegion();
    }

    /**
     * Registers a new child tenancy of {@code parentTenancyId}. Its OCID carries the realm of
     * its home region, as every tenancy OCID carries its own realm.
     */
    public synchronized StoredTenancy registerChild(String parentTenancyId, String name, String homeRegion) {
        String id = Ocids.generateGlobal("tenancy", ociContext.realmOf(homeRegion));
        StoredTenancy child = newTenancy(id, name, homeRegion, parentTenancyId);
        save(child);
        LOG.infov("Registered child tenancy {0} ({1}) of {2} in {3}", name, id, parentTenancyId, homeRegion);
        return child;
    }

    private static StoredTenancy newTenancy(String id, String name, String homeRegion, String parentTenancyId) {
        StoredTenancy tenancy = new StoredTenancy();
        tenancy.setId(id);
        tenancy.setName(name);
        tenancy.setHomeRegion(homeRegion);
        tenancy.setParentTenancyId(parentTenancyId);
        tenancy.setTimeCreated(Instant.now().toString());
        return tenancy;
    }

    private void save(StoredTenancy tenancy) {
        TenancyAwareStorageBackend.putIn(tenancies, tenancy.getId(), tenancy.getId(), tenancy);
    }
}
