package io.floci.oci.services.streaming;

import io.floci.oci.services.streaming.model.StoredStream;
import io.floci.oci.services.streaming.model.StoredStream.StoredEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The log in floci-oci's own storage, on the {@link StoredStream} record: mock mode, and streams
 * created before their pools had a broker. The caller persists the stream after an append.
 */
final class StoredStreamLog implements StreamLog {

    static final StoredStreamLog INSTANCE = new StoredStreamLog();

    private StoredStreamLog() {
    }

    @Override
    public List<Appended> append(StoredStream stream, List<Append> messages) {
        List<Appended> results = new ArrayList<>();
        for (Append message : messages) {
            List<StoredEntry> log = stream.partitionLog(message.partition());
            StoredEntry stored = new StoredEntry();
            stored.setKey(message.key());
            stored.setValue(message.value());
            stored.setOffset(log.size());
            stored.setTimestamp(Instant.now().toString());
            log.add(stored);
            results.add(new Appended(message.partition(), stored.getOffset(), stored.getTimestamp(), null));
        }
        return results;
    }

    @Override
    public List<StoredEntry> read(StoredStream stream, int partition, long offset, int max) {
        List<StoredEntry> log = stream.partitionLog(partition);
        List<StoredEntry> read = new ArrayList<>();
        for (long position = offset; position < log.size() && read.size() < max; position++) {
            read.add(log.get((int) position));
        }
        return read;
    }

    @Override
    public long endOffset(StoredStream stream, int partition) {
        return stream.partitionLog(partition).size();
    }
}
