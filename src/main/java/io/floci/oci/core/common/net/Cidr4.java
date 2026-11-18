package io.floci.oci.core.common.net;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An IPv4 CIDR block as a (network, prefix) pair, so containment and overlap checks are integer
 * comparisons rather than string handling. Addresses are unsigned values widened into a
 * {@code long}, so the whole IPv4 space compares correctly without sign trouble at 128.0.0.0.
 */
public final class Cidr4 {

    private static final Pattern CIDR = Pattern.compile(
            "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})$");

    private final long network;
    private final int prefix;

    private Cidr4(long network, int prefix) {
        this.prefix = prefix;
        this.network = network & maskFor(prefix);
    }

    /** The block, or empty when {@code text} is null, malformed, or has an octet or prefix out of range. */
    public static Optional<Cidr4> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = CIDR.matcher(text.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        long address = 0;
        for (int i = 1; i <= 4; i++) {
            long octet = Long.parseLong(m.group(i));
            if (octet > 255) {
                return Optional.empty();
            }
            address = (address << 8) | octet;
        }
        int prefix = Integer.parseInt(m.group(5));
        if (prefix > 32) {
            return Optional.empty();
        }
        return Optional.of(new Cidr4(address, prefix));
    }

    private static long maskFor(int prefix) {
        return prefix == 0 ? 0L : ((0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL);
    }

    public int prefix() {
        return prefix;
    }

    /** Number of addresses in the block, network and broadcast included. */
    public long size() {
        return 1L << (32 - prefix);
    }

    public long broadcast() {
        return network + size() - 1;
    }

    public boolean contains(Cidr4 other) {
        return other.network >= network && other.broadcast() <= broadcast();
    }

    public boolean overlaps(Cidr4 other) {
        return network <= other.broadcast() && other.network <= broadcast();
    }

    /** The address {@code offset} positions above the network address, or empty when outside the block. */
    public Optional<String> addressAt(long offset) {
        if (offset < 0 || offset >= size()) {
            return Optional.empty();
        }
        return Optional.of(format(network + offset));
    }

    private static String format(long address) {
        return ((address >> 24) & 0xFF) + "." + ((address >> 16) & 0xFF) + "."
                + ((address >> 8) & 0xFF) + "." + (address & 0xFF);
    }

    @Override
    public String toString() {
        return format(network) + "/" + prefix;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cidr4 other && other.network == network && other.prefix == prefix;
    }

    @Override
    public int hashCode() {
        return Objects.hash(network, prefix);
    }
}
