package org.floci.core.infra;

import java.util.ArrayList;
import java.util.List;

/**
 * Result model representing the audit outcome of resetting emulator state.
 */
public class ResetResult {
    private final boolean success;
    private final int resetComponentsCount;
    private final long elapsedMillis;
    private final List<String> resetDetails;

    public ResetResult(boolean success, int resetComponentsCount, long elapsedMillis, List<String> resetDetails) {
        this.success = success;
        this.resetComponentsCount = resetComponentsCount;
        this.elapsedMillis = elapsedMillis;
        this.resetDetails = resetDetails != null ? new ArrayList<>(resetDetails) : new ArrayList<>();
    }

    public boolean isSuccess() {
        return success;
    }

    public int getResetComponentsCount() {
        return resetComponentsCount;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public List<String> getResetDetails() {
        return new ArrayList<>(resetDetails);
    }
}
