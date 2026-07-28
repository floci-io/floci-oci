package io.floci.oci.services.functions;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.services.functions.model.StoredApplication;
import io.floci.oci.services.functions.model.StoredFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class FunctionsServiceTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..fntestcompartment";
    private static final String SUBNET = "ocid1.subnet.oc1.iad.testsubnet";

    private FunctionsService service;
    private FnServerManager fnServer;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServicesConfig.FunctionsServiceConfig functions =
                mock(EmulatorConfig.ServicesConfig.FunctionsServiceConfig.class);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.effectiveBaseUrl()).thenReturn("http://localhost:4599");
        lenient().when(config.services()).thenReturn(services);
        lenient().when(services.functions()).thenReturn(functions);
        lenient().when(functions.mock()).thenReturn(true);
        fnServer = mock(FnServerManager.class);
        service = new FunctionsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                config, fnServer);
    }

    private StoredApplication app(String name) {
        return service.createApplication(COMPARTMENT, name, List.of(SUBNET),
                Map.of("APP_VAR", "1"), null, null, null);
    }

    @Test
    void applicationLifecycle() {
        StoredApplication app = app("fn-app");
        assertTrue(app.getId().startsWith("ocid1.fnapp.oc1.iad."));
        assertEquals("ACTIVE", app.getLifecycleState());
        assertEquals("GENERIC_X86", app.getShape());

        service.updateApplication(app.getId(), Map.of("APP_VAR", "2"), null, null, null);
        assertEquals("2", service.getApplication(app.getId()).getConfig().get("APP_VAR"));

        service.deleteApplication(app.getId(), null);
        assertThrows(OciException.class, () -> service.getApplication(app.getId()));
    }

    @Test
    void subnetIdsAreMandatory() {
        OciException e = assertThrows(OciException.class,
                () -> service.createApplication(COMPARTMENT, "no-subnets", List.of(),
                        null, null, null, null));
        assertEquals("MissingParameter", e.getCode());
    }

    @Test
    void functionInheritsShapeAndComputesDigest() {
        StoredApplication app = app("digest-app");
        StoredFunction fn = service.createFunction(app.getId(), "hello",
                "iad.ocir.io/tenant/hello:0.0.1", 256L, null, null, null, null);
        assertTrue(fn.getId().startsWith("ocid1.fnfunc.oc1.iad."));
        assertEquals("GENERIC_X86", fn.getShape());
        assertEquals(30, fn.getTimeoutInSeconds());
        assertTrue(fn.getImageDigest().startsWith("sha256:"));

        // Terraform's CustomizeDiff contract: same image → same digest; new image → new digest.
        String originalDigest = fn.getImageDigest();
        assertEquals(originalDigest, FunctionsService.digestFor("iad.ocir.io/tenant/hello:0.0.1"));
        StoredFunction updated = service.updateFunction(fn.getId(),
                "iad.ocir.io/tenant/hello:0.0.2", null, null, null, null, null, null);
        assertNotEquals(originalDigest, updated.getImageDigest());
    }

    @Test
    void applicationWithFunctionsRefusesDeletion() {
        StoredApplication app = app("busy-app");
        service.createFunction(app.getId(), "f", "img:1", 128L, null, null, null, null);
        OciException e = assertThrows(OciException.class,
                () -> service.deleteApplication(app.getId(), null));
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void changeCompartmentMovesFunctionsImplicitly() {
        StoredApplication app = app("move-app");
        StoredFunction fn = service.createFunction(app.getId(), "f", "img:1", 128L,
                null, null, null, null);
        String other = "ocid1.compartment.oc1..otherfncompartment";
        service.changeApplicationCompartment(app.getId(), other, null);
        assertEquals(other, service.getFunction(fn.getId()).getCompartmentId());
    }

    @Test
    void mockInvocationNeverTouchesTheSidecar() {
        StoredApplication app = app("mock-app");
        StoredFunction fn = service.createFunction(app.getId(), "mocked", "img:1", 128L,
                null, null, null, null);
        FunctionsService.InvokeResult result = service.invoke(fn.getId(),
                "{}".getBytes(StandardCharsets.UTF_8), "application/json");
        assertTrue(new String(result.body()).contains("mock invocation of mocked"));
        verifyNoInteractions(fnServer);
    }

    @Test
    void listFunctionsRequiresApplicationId() {
        assertThrows(OciException.class, () -> service.listFunctions(null, null, null));
    }

    @Test
    void resetClearsStateAndStopsSidecarOnlyWhenReal() {
        StoredApplication app = app("reset-app");
        service.clear();
        assertThrows(OciException.class, () -> service.getApplication(app.getId()));
        // mock mode: the sidecar must not be touched even on reset
        verifyNoInteractions(fnServer);
    }

    @Test
    void memoryIsMandatoryOnCreate() {
        StoredApplication app = app("mem-app");
        OciException e = assertThrows(OciException.class,
                () -> service.createFunction(app.getId(), "f", "img:1", null, null, null, null, null));
        assertEquals("MissingParameter", e.getCode());
        assertNotNull(e.getMessage());
    }
}
