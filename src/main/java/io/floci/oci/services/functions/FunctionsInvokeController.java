package io.floci.oci.services.functions;

import io.floci.oci.core.common.RequestContextPropagation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;

/** The Functions invoke plane on API version {@code 20181201}; see {@link FunctionsInvokeResource}. */
@Path("/20181201/functions/{functionId}/actions/invoke")
@ApplicationScoped
public class FunctionsInvokeController extends FunctionsInvokeResource {

    @Inject
    public FunctionsInvokeController(FunctionsService service, RequestContextPropagation contextPropagation) {
        super(service, contextPropagation);
    }
}
