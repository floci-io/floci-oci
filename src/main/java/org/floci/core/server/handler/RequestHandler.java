package org.floci.core.server.handler;

import org.floci.core.server.EmulatorRequest;
import org.floci.core.server.EmulatorResponse;

/**
 * Interface implemented by cloud service controllers to handle emulator requests.
 */
@FunctionalInterface
public interface RequestHandler {
    /**
     * Handles an incoming emulator request and produces a response.
     */
    EmulatorResponse handle(EmulatorRequest request);
}
