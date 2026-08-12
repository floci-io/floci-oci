package io.floci.core.resource.compute;

/**
 * Interface contract for serverless function invocation.
 */
@FunctionalInterface
public interface ComputeFunction {
    /**
     * Executes a serverless function payload and returns the result.
     */
    ExecutionResult invoke(ExecutionPayload payload);
}
