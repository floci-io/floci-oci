package io.floci.core.infra;

/**
 * Interface implemented by emulator components that support state reset.
 */
@FunctionalInterface
public interface Resettable {
    /**
     * Resets the internal in-memory state of the component.
     */
    void reset();
}
