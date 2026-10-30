package io.floci.oci.services.functions;

import io.floci.oci.core.common.RequestContextPropagation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;

/** The Functions invoke plane on API version {@code 20260325}; see {@link FunctionsInvokeResource}. */
@Path("/20260325/functions/{functionId}/actions/invoke")
@ApplicationScoped
public class FunctionsInvokeV2Controller extends FunctionsInvokeResource {

    @Inject
    public FunctionsInvokeV2Controller(FunctionsService service, RequestContextPropagation contextPropagation) {
        super(service, contextPropagation);
    }
}
