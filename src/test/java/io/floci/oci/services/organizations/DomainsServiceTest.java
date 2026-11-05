package io.floci.oci.services.organizations;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.organizations.model.StoredDomain;
import io.floci.oci.services.organizations.model.StoredDomainGovernance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class DomainsServiceTest {

    private static final String OWNER = "ocid1.tenancy.oc1..domowner";
    private static final String OTHER = "ocid1.tenancy.oc1..domother";

    private final AtomicReference<String> caller = new AtomicReference<>(OWNER);
    private WorkRequestService workRequests;
    private DomainsService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        OciContext context = mock(OciContext.class);
        lenient().when(context.tenancyId()).thenAnswer(invocation -> caller.get());
        lenient().when(context.realm()).thenReturn("oc1");
        workRequests = new WorkRequestService(new InMemoryStorage<>(), config);
        service = new DomainsService(partitioned(new InMemoryStorage<>()), partitioned(new InMemoryStorage<>()),
                workRequests, context);
    }

    private <V> TenancyAwareStorageBackend<V> partitioned(StorageBackend<String, V> raw) {
        return new TenancyAwareStorageBackend<>(raw, caller::get);
    }

    private StoredDomain claim(String name) {
        return service.createDomain(caller.get(), name, null, null, null, null).domain();
    }

    private StoredDomainGovernance govern(StoredDomain domain) {
        return service.createDomainGovernance(caller.get(), domain.getId(), "ops@example.com",
                "ocid1.onstopic.oc1.iad.t", "ocid1.onssubscription.oc1.iad.s", null, null);
    }

    @Test
    void createDomainClaimsItAtOnceWithATxtRecord() {
        DomainsService.Created created = service.createDomain(OWNER, "Example.COM", "ops@example.com", true,
                null, null);

        StoredDomain domain = created.domain();
        assertEquals("example.com", domain.getDomainName());
        assertEquals("ACTIVE", domain.getStatus());
        assertEquals("ACTIVE", domain.getLifecycleState());
        assertEquals(OWNER, domain.getOwnerId());
        assertTrue(domain.getTxtRecord().startsWith("oci-domain-verification="));
        assertEquals("REGISTER_DOMAIN", workRequests.get(created.workRequestId()).getOperationType());
    }

    @Test
    void aClaimedDomainCannotBeClaimedAgainUntilReleased() {
        StoredDomain domain = claim("example.com");
        caller.set(OTHER);

        assertEquals("Conflict", assertThrows(OciException.class, () -> claim("example.com")).getCode());

        caller.set(OWNER);
        service.deleteDomain(domain.getId(), null);
        caller.set(OTHER);
        assertEquals(OTHER, claim("example.com").getOwnerId());
    }

    @Test
    void releasingKeepsAReadableRecordAndDeactivatesGovernance() {
        StoredDomain domain = claim("example.com");
        StoredDomainGovernance governance = govern(domain);

        service.deleteDomain(domain.getId(), null);

        StoredDomain released = service.getDomain(domain.getId());
        assertEquals("DELETED", released.getLifecycleState());
        assertEquals("RELEASED", released.getStatus());
        assertEquals("INACTIVE", service.getDomainGovernance(governance.getId()).getLifecycleState());
        assertEquals("Conflict", assertThrows(OciException.class,
                () -> service.deleteDomain(domain.getId(), null)).getCode());
        assertEquals("Conflict", assertThrows(OciException.class,
                () -> service.updateDomain(domain.getId(), null, null, null)).getCode());
    }

    @Test
    void createDomainValidatesItsInput() {
        assertEquals("MissingParameter", assertThrows(OciException.class,
                () -> service.createDomain(OWNER, null, null, null, null, null)).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> service.createDomain(OWNER, "not a domain", null, null, null, null)).getCode());
        assertEquals(404, assertThrows(OciException.class,
                () -> service.createDomain(OTHER, "example.com", null, null, null, null)).getHttpStatus());
        assertEquals("xn--80ak6aa92e.xn--p1ai", claim("xn--80ak6aa92e.xn--p1ai").getDomainName(), "punycode TLD");
    }

    @Test
    void oneActiveGovernancePerDomain() {
        StoredDomain domain = claim("example.com");
        StoredDomainGovernance governance = govern(domain);

        assertEquals(true, governance.getIsGovernanceEnabled());
        assertEquals("Conflict", assertThrows(OciException.class, () -> govern(domain)).getCode());

        service.deleteDomainGovernance(governance.getId(), null);
        assertEquals("INACTIVE", service.getDomainGovernance(governance.getId()).getLifecycleState());
        assertEquals("ACTIVE", govern(domain).getLifecycleState());
    }

    @Test
    void governanceNeedsTheOnsReferencesAndAnActiveDomain() {
        StoredDomain domain = claim("example.com");

        assertEquals("MissingParameter", assertThrows(OciException.class, () -> service.createDomainGovernance(
                OWNER, domain.getId(), "ops@example.com", null, "s", null, null)).getCode());
        service.deleteDomain(domain.getId(), null);
        assertEquals("Conflict", assertThrows(OciException.class, () -> govern(domain)).getCode());
    }

    @Test
    void updateDomainGovernanceChecksTheEtag() {
        StoredDomainGovernance governance = govern(claim("example.com"));
        String etag = governance.getEtag();

        assertEquals(412, assertThrows(OciException.class, () -> service.updateDomainGovernance(
                governance.getId(), null, false, null, null, "stale")).getHttpStatus());
        StoredDomainGovernance updated = service.updateDomainGovernance(governance.getId(), "new@example.com",
                false, null, null, etag);

        assertEquals(false, updated.getIsGovernanceEnabled());
        assertEquals("new@example.com", updated.getSubscriptionEmail());
    }

    @Test
    void domainsAreVisibleOnlyToTheirOwner() {
        StoredDomain domain = claim("example.com");
        caller.set(OTHER);

        assertEquals(404, assertThrows(OciException.class, () -> service.getDomain(domain.getId())).getHttpStatus());
        assertTrue(service.listDomains(OTHER, null, null, null, null, null, null).isEmpty());
    }

    @Test
    void listDomainsFiltersAndValidates() {
        claim("b-example.com");
        claim("a-example.com");

        assertEquals(List.of("a-example.com", "b-example.com"), service.listDomains(OWNER, null, null, null, null,
                "displayName", null).stream().map(StoredDomain::getDomainName).toList());
        assertEquals(1, service.listDomains(OWNER, null, "ACTIVE", "ACTIVE", "A-EXAMPLE.COM", null, null).size());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> service.listDomains(OWNER, null, "DELETED", null, null, null, null)).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class,
                () -> service.listDomains(OWNER, null, null, "VERIFIED", null, null, null)).getCode());
        assertEquals("MissingParameter", assertThrows(OciException.class,
                () -> service.listDomains(null, null, null, null, null, null, null)).getCode());
    }
}
