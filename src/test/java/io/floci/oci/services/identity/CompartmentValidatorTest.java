package io.floci.oci.services.identity;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.model.StoredCompartment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        lenient().when(config.services().identity().strictCompartments()).thenReturn(true);
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
    void anyCompartmentPassesWhenTheFlagIsOffOrIdentityDisabled() {
        lenient().when(config.services().identity().strictCompartments()).thenReturn(false);
        assertDoesNotThrow(() -> validator.requireInBody(UNKNOWN));

        lenient().when(config.services().identity().strictCompartments()).thenReturn(true);
        lenient().when(config.services().identity().enabled()).thenReturn(false);
        assertDoesNotThrow(() -> validator.requireInQuery(UNKNOWN));
    }
}
