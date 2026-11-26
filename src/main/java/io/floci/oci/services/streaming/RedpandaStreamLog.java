package io.floci.oci.services.streaming;

import io.floci.oci.services.streaming.model.StoredStream;
import io.floci.oci.services.streaming.model.StoredStream.StoredEntry;
import io.floci.oci.services.streaming.model.StoredStreamPool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The log in the stream's topic on its pool's Redpanda broker, which is the only copy: Kafka
 * clients and the OCI API read and write the same records at the same offsets. The topic is
 * the stream's name, as on OCI's Kafka endpoint.
 */
final class RedpandaStreamLog implements StreamLog {

    private final StreamPoolKafkaManager kafka;
    private final StoredStreamPool pool;

    RedpandaStreamLog(StreamPoolKafkaManager kafka, StoredStreamPool pool) {
        this.kafka = kafka;
        this.pool = pool;
    }

    /**
     * Produces through the broker's HTTP proxy. The proxy does not return the timestamp the
     * broker gave each record, so the time of the produce stands in for it.
     */
    @Override
    public List<Appended> append(StoredStream stream, List<Append> messages) {
        List<KafkaRestClient.ProducedRecord> records = new ArrayList<>();
        for (Append message : messages) {
            records.add(new KafkaRestClient.ProducedRecord(message.partition(), message.key(), message.value()));
        }
        List<KafkaRestClient.RecordOffset> offsets = kafka.produce(pool, stream.getName(), records);
        String now = Instant.now().toString();
        List<Appended> results = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            KafkaRestClient.RecordOffset offset = offsets.get(i);
            results.add(offset.error() != null
                    ? new Appended(messages.get(i).partition(), -1, null, offset.error())
                    : new Appended(messages.get(i).partition(), offset.offset(), now, null));
        }
        return results;
    }

    @Override
    public List<StoredEntry> read(StoredStream stream, int partition, long offset, int max) {
        return kafka.read(pool, stream.getName(), partition, offset, max);
    }

    @Override
    public long endOffset(StoredStream stream, int partition) {
        return kafka.endOffset(pool, stream.getName(), partition);
    }
}
