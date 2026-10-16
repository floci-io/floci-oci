package io.floci.oci.services.identity;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.model.StoredCompartment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class CompartmentValidatorTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..testtenancy";
    private static final String UNKNOWN = "ocid1.compartment.oc1..unknown";

    private EmulatorConfig config;
    private IdentityService identity;
    private CompartmentValidator validator;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultTenancyId()).thenReturn(TENANCY);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.services().identity().enabled()).thenReturn(true);
        identity = new IdentityService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), config,
                new WorkRequestService(new InMemoryStorage<>(), config));
        validator = new CompartmentValidator(config, identity);
    }

    @Test
    void unknownCompartmentFailsByWhereItTravels() {
        OciException body = assertThrows(OciException.class, () -> validator.requireInBody(UNKNOWN));
        assertEquals(400, body.getHttpStatus());
        assertEquals("RelatedResourceNotAuthorizedOrNotFound", body.getCode());

        OciException query = assertThrows(OciException.class, () -> validator.requireInQuery(UNKNOWN));
        assertEquals(404, query.getHttpStatus());
        assertEquals("NotAuthorizedOrNotFound", query.getCode());
    }

    @Test
    void activeCompartmentsAndTheTenancyPass() {
        StoredCompartment dev = identity.createCompartment(null, "dev", "d", null, null);
        assertDoesNotThrow(() -> validator.requireInBody(dev.getId()));
        assertDoesNotThrow(() -> validator.requireInQuery(TENANCY));
        assertDoesNotThrow(() -> validator.requireInBody(null));
    }

    @Test
    void explicitlyBlankCompartmentIsRejected() {
        assertThrows(OciException.class, () -> validator.requireInBody(" "));
        assertThrows(OciException.class, () -> validator.requireInQuery(""));
    }

    @Test
    void deletedCompartmentIsRejected() {
        StoredCompartment gone = identity.createCompartment(null, "gone", "d", null, null);
        identity.deleteCompartment(gone.getId(), null);
        assertThrows(OciException.class, () -> validator.requireInBody(gone.getId()));
    }

    @Test
    void anyCompartmentPassesWhenIdentityIsDisabled() {
        lenient().when(config.services().identity().enabled()).thenReturn(false);
        assertDoesNotThrow(() -> validator.requireInBody(UNKNOWN));
        assertDoesNotThrow(() -> validator.requireInQuery(UNKNOWN));
    }

    @Test
    void deletionWaitsForACreationInProgress() throws Exception {
        StoredCompartment dev = identity.createCompartment(null, "dev", "d", null, null);

        CompletableFuture<String> deletion;
        try (CompartmentValidator.Use ignored = validator.useInBody(dev.getId())) {
            deletion = CompletableFuture.supplyAsync(() -> identity.deleteCompartment(dev.getId(), null));
            assertThrows(TimeoutException.class, () -> deletion.get(300, TimeUnit.MILLISECONDS));
            assertEquals("ACTIVE", identity.getCompartment(dev.getId()).getLifecycleState());
        }

        deletion.get(5, TimeUnit.SECONDS);
        assertEquals("DELETED", identity.getCompartment(dev.getId()).getLifecycleState());
    }

    @Test
    void creationAfterADeletionIsRejectedAndLeavesNoLockHeld() throws Exception {
        StoredCompartment gone = identity.createCompartment(null, "gone", "d", null, null);
        identity.deleteCompartment(gone.getId(), null);

        assertThrows(OciException.class, () -> validator.useInBody(gone.getId()));

        CompletableFuture<String> again = CompletableFuture.supplyAsync(
                () -> identity.deleteCompartment(gone.getId(), null));
        again.get(5, TimeUnit.SECONDS);
        assertFalse(again.isCompletedExceptionally());
    }

    @Test
    void unknownCompartmentIdsDoNotGrowTheLocks() {
        Set<Object> locks = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < 10_000; i++) {
            String id = "ocid1.compartment.oc1..missing" + i;
            assertThrows(OciException.class, () -> validator.useInBody(id));
            locks.add(identity.compartmentUseLock(id));
        }
        assertTrue(locks.size() <= IdentityService.COMPARTMENT_LOCK_STRIPES);
        assertSame(identity.compartmentUseLock(UNKNOWN), identity.compartmentUseLock(UNKNOWN));
    }
}
