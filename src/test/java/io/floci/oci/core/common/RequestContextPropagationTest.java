package io.floci.oci.core.common;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class RequestContextPropagationTest {

    private static final String TENANCY = "ocid1.tenancy.oc1..propagatedtenancy";
    private static final String DEFAULT_TENANCY =
            "ocid1.tenancy.oc1..flocitesttenancy00000000000000000000000000000000000000000";

    @Inject
    RequestContext requestContext;

    @Inject
    OciContext ociContext;

    @Inject
    RequestContextPropagation propagation;

    @Test
    void workOnAnotherThreadSeesTheCallersTenancyAndRegion() throws InterruptedException {
        requestContext.setTenancyId(TENANCY);
        requestContext.setRegion("us-phoenix-1");
        AtomicReference<String> seen = new AtomicReference<>();

        Thread.ofVirtual().start(propagation.withCurrentContext(
                () -> seen.set(ociContext.tenancyId() + " " + ociContext.region()))).join();

        assertEquals(TENANCY + " us-phoenix-1", seen.get());
    }

    @Test
    void withoutPropagationTheDefaultsApply() throws InterruptedException {
        requestContext.setTenancyId(TENANCY);
        requestContext.setRegion("us-phoenix-1");
        AtomicReference<String> seen = new AtomicReference<>();

        Thread.ofVirtual().start(() -> seen.set(ociContext.tenancyId() + " " + ociContext.region())).join();

        assertEquals(DEFAULT_TENANCY + " us-ashburn-1", seen.get());
    }
}
