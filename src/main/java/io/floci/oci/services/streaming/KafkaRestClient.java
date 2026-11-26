package io.floci.oci.services.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin client for the part of the Kafka REST Proxy v2 API (Redpanda's built-in HTTP proxy) that
 * Streaming needs: producing records to a topic and checking that the proxy answers. Ported from
 * floci-aws {@code KafkaRestClient}, which talks to Kafka over HTTP instead of embedding
 * {@code kafka-clients}: that library (and the compression codecs it pulls in) breaks the native
 * image (floci-aws #2916). Only {@link #produce} is new; the consumer calls are left out because
 * reads go through {@code rpk} in the broker container, which also returns record timestamps.
 */
@ApplicationScoped
public class KafkaRestClient {

    private static final String BINARY_CONTENT_TYPE = "application/vnd.kafka.binary.v2+json";
    private static final String V2_ACCEPT = "application/vnd.kafka.v2+json";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    /** One record to produce: the partition, and the base64 key (or null) and value. */
    public record ProducedRecord(int partition, String keyBase64, String valueBase64) {
    }

    /** Where a produced record landed, or the error the proxy reported for it. */
    public record RecordOffset(int partition, long offset, String error) {
    }

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Inject
    public KafkaRestClient(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    KafkaRestClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * Produces {@code records} to {@code topic} in one request ({@code POST /topics/{topic}}) and
     * returns each record's offset in request order. Redpanda answers with one entry per record
     * or, when it batches the records of one partition, one entry per partition carrying the
     * batch's base offset: the records of a batch then hold consecutive offsets in request order.
     */
    public List<RecordOffset> produce(URI proxyBaseUri, String topic, List<ProducedRecord> records) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode recordsNode = body.putArray("records");
        for (ProducedRecord record : records) {
            ObjectNode node = recordsNode.addObject();
            if (record.keyBase64() != null) {
                node.put("key", record.keyBase64());
            }
            node.put("value", record.valueBase64());
            node.put("partition", record.partition());
        }
        JsonNode response = send("POST", proxyBaseUri.resolve("topics/" + urlEncode(topic)), body, REQUEST_TIMEOUT);
        JsonNode offsets = response.path("offsets");
        List<RecordOffset> results = new ArrayList<>();
        if (offsets.size() == records.size()) {
            for (JsonNode offset : offsets) {
                results.add(toOffset(offset, offset.path("offset").asLong(-1)));
            }
            return results;
        }
        List<JsonNode> perPartition = new ArrayList<>();
        offsets.forEach(perPartition::add);
        for (int i = 0; i < records.size(); i++) {
            int partition = records.get(i).partition();
            JsonNode batch = perPartition.stream()
                    .filter(node -> node.path("partition").asInt(-1) == partition)
                    .findFirst()
                    .orElse(null);
            if (batch == null) {
                results.add(new RecordOffset(partition, -1, "no offset returned for partition " + partition));
                continue;
            }
            long base = batch.path("offset").asLong(-1);
            long ordinal = 0;
            for (int j = 0; j < i; j++) {
                if (records.get(j).partition() == partition) {
                    ordinal++;
                }
            }
            results.add(toOffset(batch, base < 0 ? -1 : base + ordinal));
        }
        return results;
    }

    /** Whether the proxy answers and reaches its broker: {@code GET /topics} succeeds. */
    public boolean isReady(URI proxyBaseUri) {
        try {
            send("GET", proxyBaseUri.resolve("topics"), null, PROBE_TIMEOUT);
            return true;
        } catch (RuntimeException expected) {
            // Not listening yet, or no broker behind it: the caller polls again.
            return false;
        }
    }

    private static RecordOffset toOffset(JsonNode node, long offset) {
        int partition = node.path("partition").asInt(-1);
        if (node.hasNonNull("error_code") || offset < 0) {
            String error = node.path("error").asText(node.path("error_code").asText("offset missing"));
            return new RecordOffset(partition, -1, error);
        }
        return new RecordOffset(partition, offset, null);
    }

    private JsonNode send(String method, URI uri, JsonNode body, Duration timeout) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", V2_ACCEPT);
            if (body != null) {
                builder.header("Content-Type", BINARY_CONTENT_TYPE)
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new KafkaRestClientException(method + " " + uri + " returned " + response.statusCode()
                        + ": " + new String(response.body(), StandardCharsets.UTF_8), response.statusCode());
            }
            return response.body().length == 0 ? NullNode.getInstance() : objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException("Kafka REST Proxy " + method + " " + uri + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling Kafka REST Proxy " + method + " " + uri, e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** A non-2xx answer from the proxy. */
    public static class KafkaRestClientException extends RuntimeException {

        private final int statusCode;

        public KafkaRestClientException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
