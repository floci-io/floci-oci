package io.floci.oci.services.objectstorage;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Decodes an {@code aws-chunked} body as it is read, for the S3 Compatibility API, which accepts
 * {@code STREAMING-AWS4-HMAC-SHA256-PAYLOAD}, {@code STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER} and
 * {@code STREAMING-UNSIGNED-PAYLOAD-TRAILER} uploads (Object/Tasks/s3compatibleapi). Each chunk
 * header ({@code hex-size[;chunk-signature=...]}) is consumed and only chunk data comes out, up to
 * the final empty chunk, its trailer lines and the closing empty line. Chunk signatures and
 * trailing checksums are not checked, as no request signature is. Adapted from floci-aws.
 */
final class AwsChunkedInputStream extends InputStream {

    /** A chunk header or trailer line is a size, a signature and a few separators. */
    private static final int MAX_LINE_LENGTH = 8192;

    private final InputStream in;
    private long remainingInChunk;
    private boolean finished;

    AwsChunkedInputStream(InputStream in) {
        this.in = new BufferedInputStream(in);
    }

    static boolean isAwsChunked(String contentEncoding, String contentSha256) {
        return (contentEncoding != null && contentEncoding.toLowerCase().contains("aws-chunked"))
                || (contentSha256 != null && contentSha256.startsWith("STREAMING-"));
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        int read = read(single, 0, 1);
        return read < 0 ? -1 : single[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (remainingInChunk == 0 && !nextChunk()) {
            return -1;
        }
        int read = in.read(buffer, offset, (int) Math.min(length, remainingInChunk));
        if (read < 0) {
            throw incompleteBody();
        }
        remainingInChunk -= read;
        if (remainingInChunk == 0) {
            readLineBreak();
        }
        return read;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    private boolean nextChunk() throws IOException {
        if (finished) {
            return false;
        }
        String header = readLine();
        int semicolon = header.indexOf(';');
        String hexSize = (semicolon >= 0 ? header.substring(0, semicolon) : header).trim();
        long size;
        try {
            size = Long.parseLong(hexSize, 16);
        } catch (NumberFormatException e) {
            throw incompleteBody();
        }
        if (size < 0) {
            throw incompleteBody();
        }
        if (size > 0) {
            remainingInChunk = size;
            return true;
        }
        // The final chunk: trailer lines (x-amz-checksum-*), then the empty line that ends the body.
        String trailer = readLine();
        while (!trailer.isEmpty()) {
            trailer = readLine();
        }
        finished = true;
        return false;
    }

    private void readLineBreak() throws IOException {
        int next = in.read();
        if (next == '\r') {
            next = in.read();
        }
        if (next != '\n') {
            throw incompleteBody();
        }
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int next = in.read(); next != '\n'; next = in.read()) {
            if (next < 0) {
                // A body that ends right after the final chunk's line break has no trailer section.
                if (line.size() == 0 && finishedFramingAllowed()) {
                    return "";
                }
                throw incompleteBody();
            }
            if (line.size() >= MAX_LINE_LENGTH) {
                throw incompleteBody();
            }
            line.write(next);
        }
        return line.toString(StandardCharsets.ISO_8859_1).trim();
    }

    private boolean finishedFramingAllowed() {
        return remainingInChunk == 0;
    }

    private static IOException incompleteBody() {
        return new IOException("IncompleteBody: the aws-chunked body ended early or broke its framing");
    }
}
