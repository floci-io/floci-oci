package io.floci.oci.services.streaming;

import io.floci.oci.services.streaming.model.StoredStream;
import io.floci.oci.services.streaming.model.StoredStream.StoredEntry;

import java.util.List;

/**
 * Where a stream's messages live. OCI offsets are the log's own offsets, so cursors read the
 * same way whatever the backend: {@link StoredStreamLog} keeps them in floci-oci's storage,
 * {@link RedpandaStreamLog} in the stream's topic on its pool's broker.
 */
interface StreamLog {

    /** One message to append: its partition, and its base64 key (or null) and value. */
    record Append(int partition, String key, String value) {
    }

    /** Where an appended message landed, or why it was refused. */
    record Appended(int partition, long offset, String timestamp, String error) {
    }

    /** Appends {@code messages} and returns where each landed, in the same order. */
    List<Appended> append(StoredStream stream, List<Append> messages);

    /** Up to {@code max} messages of {@code partition}, starting at {@code offset}. */
    List<StoredEntry> read(StoredStream stream, int partition, long offset, int max);

    /** The offset the next message of {@code partition} will get. */
    long endOffset(StoredStream stream, int partition);
}
