package org.floci.core.server.filter;

import org.floci.core.server.EmulatorResponse;

/**
 * Encapsulates the outcome of filter execution, supporting request continuation or early short-circuiting.
 */
public class FilterResult {
    private final boolean continueChain;
    private final EmulatorResponse shortCircuitResponse;

    private FilterResult(boolean continueChain, EmulatorResponse shortCircuitResponse) {
        this.continueChain = continueChain;
        this.shortCircuitResponse = shortCircuitResponse;
    }

    public static FilterResult next() {
        return new FilterResult(true, null);
    }

    public static FilterResult shortCircuit(EmulatorResponse response) {
        return new FilterResult(false, response);
    }

    public boolean isContinueChain() {
        return continueChain;
    }

    public EmulatorResponse getShortCircuitResponse() {
        return shortCircuitResponse;
    }
}
