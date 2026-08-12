package io.floci.core.resource.compute;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Event and context payload for serverless function execution across Lambda, Azure Functions, and Cloud Functions.
 */
public class ExecutionPayload {
    private final String functionName;
    private final byte[] eventData;
    private final Map<String, String> environmentVariables;

    public ExecutionPayload(String functionName, byte[] eventData, Map<String, String> environmentVariables) {
        this.functionName = functionName != null ? functionName : "defaultFunction";
        this.eventData = eventData != null ? eventData : new byte[0];
        this.environmentVariables = environmentVariables != null ? new HashMap<>(environmentVariables) : new HashMap<>();
    }

    public static ExecutionPayload of(String functionName, String jsonEvent) {
        return new ExecutionPayload(functionName, jsonEvent != null ? jsonEvent.getBytes(StandardCharsets.UTF_8) : new byte[0], Map.of());
    }

    public String getFunctionName() {
        return functionName;
    }

    public byte[] getEventData() {
        return eventData.clone();
    }

    public String getEventDataAsString() {
        return new String(eventData, StandardCharsets.UTF_8);
    }

    public Map<String, String> getEnvironmentVariables() {
        return Collections.unmodifiableMap(environmentVariables);
    }
}
