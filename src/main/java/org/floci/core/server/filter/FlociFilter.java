package org.floci.core.server.filter;

import org.floci.core.server.EmulatorRequest;

/**
 * Filter contract for request interceptors across cloud emulators.
 */
public interface FlociFilter extends Comparable<FlociFilter> {

    /**
     * Priority order for filter execution. Lower values execute earlier.
     */
    default int getOrder() {
        return 0;
    }

    /**
     * Intercepts and filters an incoming request.
     */
    FilterResult doFilter(EmulatorRequest request);

    @Override
    default int compareTo(FlociFilter o) {
        return Integer.compare(this.getOrder(), o.getOrder());
    }
}
