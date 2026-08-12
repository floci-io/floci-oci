package org.floci.core.server.filter;

import org.floci.core.server.EmulatorRequest;
import org.floci.core.server.EmulatorResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Priority-ordered pipeline executor for request filters.
 */
public class FilterChain {
    private final List<FlociFilter> filters = new CopyOnWriteArrayList<>();

    public void addFilter(FlociFilter filter) {
        if (filter != null && !filters.contains(filter)) {
            filters.add(filter);
            Collections.sort(filters);
        }
    }

    public void removeFilter(FlociFilter filter) {
        filters.remove(filter);
    }

    public List<FlociFilter> getFilters() {
        return Collections.unmodifiableList(filters);
    }

    public FilterResult execute(EmulatorRequest request) {
        List<FlociFilter> activeFilters = new ArrayList<>(filters);
        for (FlociFilter filter : activeFilters) {
            FilterResult result = filter.doFilter(request);
            if (result != null && !result.isContinueChain()) {
                return result;
            }
        }
        return FilterResult.next();
    }
}
