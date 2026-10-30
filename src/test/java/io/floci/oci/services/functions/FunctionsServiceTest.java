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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
    void settingsReadBackAsSentAndMergeOnUpdate() {
        StoredApplication app = service.createApplication(COMPARTMENT, "settings-app", List.of(SUBNET), null, null,
                null, null, Map.of("syslogUrl", "tcp://logs:514", "traceConfig", Map.of("isEnabled", false)));

        service.updateApplication(app.getId(), null, null, null, Map.of("traceConfig", Map.of("isEnabled", true)),
                null);

        assertEquals(Map.of("syslogUrl", "tcp://logs:514", "traceConfig", Map.of("isEnabled", true)),
                service.getApplication(app.getId()).getSettings());
        assertFalse(FunctionsWire.application(service.getApplication(app.getId()), false).containsKey("syslogUrl"),
                "ApplicationSummary has no syslogUrl");
    }

    @Test
    void anUnknownShapeIsRejected() {
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> service.createApplication(COMPARTMENT,
                "bad-shape", List.of(SUBNET), null, "GENERIC_RISCV", null, null)).getCode());
    }

    @Test
    void anExplicitDigestIsKept() {
        StoredFunction fn = service.createFunction(app("digest-app").getId(), "digest-fn", "iad.ocir.io/t/fn:1",
                "sha256:abc", 128L, null, null, null, null, null);

        assertEquals("sha256:abc", fn.getImageDigest());
        Map<String, Object> v2 = FunctionsWire.function(fn, true, "http://localhost:4599", true);
        assertEquals(Map.of("sourceType", "CONTAINER_IMAGE", "image", "iad.ocir.io/t/fn:1", "imageDigest", "sha256:abc"),
                v2.get("sourceDetails"));
        assertFalse(v2.containsKey("image"));
        assertEquals("iad.ocir.io/t/fn:1", FunctionsWire.function(fn, true, "http://localhost:4599", false).get("image"));
    }

    @Test
    void aNewDigestForTheSameImageRedeploysIt() {
        StoredFunction fn = service.createFunction(app("redeploy-app").getId(), "redeploy-fn", "iad.ocir.io/t/fn:latest",
                "sha256:one", 128L, null, null, null, null, null);
        fn.setFnFnId("fn-mirror");

        service.updateFunction(fn.getId(), "iad.ocir.io/t/fn:latest", "sha256:two", null, null, null, null, null,
                null, null);

        StoredFunction updated = service.getFunction(fn.getId());
        assertEquals("sha256:two", updated.getImageDigest());
        assertNull(updated.getFnFnId(), "the next invoke mirrors the new image");

        service.updateFunction(fn.getId(), null, "sha256:three", null, null, null, null, null, null, null);
        assertEquals("iad.ocir.io/t/fn:latest", service.getFunction(fn.getId()).getImage());
        assertEquals("sha256:three", service.getFunction(fn.getId()).getImageDigest());
    }

    @Test
    void aSuppliedDigestPinsTheImageFnServerRuns() {
        StoredFunction pinned = service.createFunction(app("pin-app").getId(), "pinned", "iad.ocir.io/t/fn:latest",
                "sha256:abc", 128L, null, null, null, null, null);
        StoredFunction derived = service.createFunction(app("pin-app-2").getId(), "derived", "iad.ocir.io/t/fn:1",
                null, 128L, null, null, null, null, null);

        assertEquals("iad.ocir.io/t/fn:latest@sha256:abc", FunctionsService.imageReference(pinned));
        assertEquals("iad.ocir.io/t/fn:1", FunctionsService.imageReference(derived),
                "the derived placeholder is not a registry digest");
    }

    @Test
    void settingsEnumsAreChecked() {
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> FunctionsRequests.settings(
                Map.of("logging", Map.of("lineFormat", "XML")), FunctionsRequests.APPLICATION_SETTINGS)).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> FunctionsRequests.settings(
                Map.of("failureDestination", Map.of("kind", "EMAIL")), FunctionsRequests.FUNCTION_SETTINGS)).getCode());
        assertEquals(Map.of("provisionedConcurrencyConfig", Map.of("strategy", "NONE")), FunctionsRequests.settings(
                Map.of("provisionedConcurrencyConfig", Map.of("strategy", "NONE"), "image", "x"),
                FunctionsRequests.FUNCTION_SETTINGS));
    }

    @Test
    void workRequestsAreSucceededWithTheirResource() {
        String workRequestId = service.workRequest("CREATE_APPLICATION", COMPARTMENT, "functionsApplication",
                "CREATED", "ocid1.fnapp.oc1.iad.x", "/20260325/applications/ocid1.fnapp.oc1.iad.x");

        assertTrue(workRequestId.startsWith("ocid1."));
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
