package io.floci.core.resource.compute;

import java.nio.charset.StandardCharsets;

/**
 * Result outcome of serverless compute function invocation.
 */
public class ExecutionResult {
    private final int statusCode;
    private final byte[] resultData;
    private final String error;

    public ExecutionResult(int statusCode, byte[] resultData, String error) {
        this.statusCode = statusCode;
        this.resultData = resultData != null ? resultData : new byte[0];
        this.error = error;
    }

    public static ExecutionResult success(String output) {
        return new ExecutionResult(200, output != null ? output.getBytes(StandardCharsets.UTF_8) : new byte[0], null);
    }

    public static ExecutionResult failure(int statusCode, String errorMessage) {
        return new ExecutionResult(statusCode, new byte[0], errorMessage);
    }

    public int getStatusCode() {
        return statusCode;
    }

    public byte[] getResultData() {
        return resultData.clone();
    }

    public String getResultDataAsString() {
        return new String(resultData, StandardCharsets.UTF_8);
    }

    public String getError() {
        return error;
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300 && error == null;
    }
}
