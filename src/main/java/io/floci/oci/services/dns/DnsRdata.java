package io.floci.oci.services.dns;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.dns.DnsRecord;
import io.netty.util.NetUtil;

import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Record data in the presentation form the DNS service returns. "All RDATA is normalized and the
 * returned presentation of your RDATA may differ from its initial input" (oci-go-sdk dns/record.go);
 * the transforms are the ones Terraform's {@code normalizeRData} undoes
 * (terraform-provider-oci internal/service/dns/dns_record_resource.go): AAAA is compressed, names
 * in ALIAS, CNAME, NS, MX and PTR gain a trailing dot, and every TXT string is quoted.
 */
final class DnsRdata {

    /** The record types of Oracle's "Supported DNS Resource Record Types" page that floci-oci accepts. */
    static final Set<String> RTYPES = Set.of("A", "AAAA", "ALIAS", "CAA", "CERT", "CNAME", "DNAME", "DNSKEY", "DS",
            "HINFO", "HTTPS", "LOC", "MX", "NAPTR", "NS", "PTR", "SOA", "SPF", "SRV", "SSHFP", "SVCB", "TLSA", "TXT");

    private static final Set<String> NAME_TYPES = Set.of("ALIAS", "CNAME", "DNAME", "NS", "PTR");
    private static final Pattern LABEL = Pattern.compile("^(\\*|[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?)$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private DnsRdata() {
    }

    /** A domain name without its trailing dot, validated label by label. */
    static String domainName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw OciException.missingParameter(field + " is required");
        }
        String name = value.trim();
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isEmpty() || name.length() > 253) {
            throw OciException.invalidParameter(field + " is not a valid domain name: " + value);
        }
        for (String label : name.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                throw OciException.invalidParameter(field + " is not a valid domain name: " + value);
            }
        }
        return name;
    }

    /** True when {@code domain} is {@code zone} itself or a name beneath it. */
    static boolean isWithin(String domain, String zone) {
        String d = domain.toLowerCase(Locale.ROOT);
        String z = zone.toLowerCase(Locale.ROOT);
        return d.equals(z) || d.endsWith("." + z);
    }

    static String rtype(String value) {
        if (value == null || value.isBlank()) {
            throw OciException.missingParameter("rtype is required");
        }
        String rtype = value.trim().toUpperCase(Locale.ROOT);
        if (!RTYPES.contains(rtype)) {
            throw OciException.invalidParameter("Unsupported rtype " + value);
        }
        return rtype;
    }

    static String normalize(String rtype, String rdata) {
        if (rdata == null || rdata.isBlank()) {
            throw OciException.missingParameter("rdata is required");
        }
        String value = rdata.trim();
        return switch (rtype) {
            case "A" -> {
                if (!NetUtil.isValidIpV4Address(value)) {
                    throw OciException.invalidParameter("A rdata must be an IPv4 address, got " + rdata);
                }
                yield value;
            }
            case "AAAA" -> {
                if (!NetUtil.isValidIpV6Address(value)) {
                    throw OciException.invalidParameter("AAAA rdata must be an IPv6 address, got " + rdata);
                }
                yield compressed(value);
            }
            case "TXT", "SPF" -> quoted(value);
            case "MX" -> {
                String[] fields = WHITESPACE.split(value);
                if (fields.length != 2 || !fields[0].matches("\\d{1,5}")) {
                    throw OciException.invalidParameter("MX rdata must be a preference and an exchange, got " + rdata);
                }
                yield fields[0] + " " + absolute(fields[1]);
            }
            default -> {
                String collapsed = WHITESPACE.matcher(value).replaceAll(" ");
                yield NAME_TYPES.contains(rtype) ? absolute(collapsed) : collapsed;
            }
        };
    }

    /**
     * RFC 5952 form, with an IPv4-mapped address kept as IPv6 in the mixed notation of RFC 5952 section 5
     * ({@code ::ffff:192.0.2.1}). Go's {@code net.IP.String} prints that one as dotted IPv4, but Terraform
     * passes both sides through it, so either echo compares equal.
     */
    private static String compressed(String ipv6) {
        try {
            byte[] bytes = DnsRecord.Address.ipv6Bytes(ipv6);
            return NetUtil.toAddressString(Inet6Address.getByAddress(null, bytes, -1), true);
        } catch (IllegalArgumentException | UnknownHostException e) {
            throw OciException.invalidParameter("AAAA rdata must be an IPv6 address, got " + ipv6);
        }
    }

    private static String absolute(String name) {
        return name.endsWith(".") ? name : name + ".";
    }

    /** Each whitespace-delimited word or quoted string becomes its own quoted character string. */
    private static String quoted(String value) {
        List<String> strings = new ArrayList<>();
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            StringBuilder text = new StringBuilder();
            if (c == '"') {
                i++;
                while (i < value.length() && value.charAt(i) != '"') {
                    if (value.charAt(i) == '\\' && i + 1 < value.length()) {
                        text.append(value.charAt(i++));
                    }
                    text.append(value.charAt(i++));
                }
                if (i >= value.length()) {
                    throw OciException.invalidParameter("TXT rdata has an unterminated quoted string: " + value);
                }
                i++;
            } else {
                while (i < value.length() && !Character.isWhitespace(value.charAt(i))) {
                    char ch = value.charAt(i++);
                    if (ch == '"') {
                        text.append('\\');
                    }
                    text.append(ch);
                }
            }
            strings.add("\"" + text + "\"");
        }
        return String.join(" ", strings);
    }

    /** "A unique identifier for the record within its zone" (dns/record.go): a digest of what makes it unique. */
    static String recordHash(String domain, String rtype, String rdata, int ttl) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest((domain.toLowerCase(Locale.ROOT) + "|" + rtype + "|" + rdata + "|" + ttl)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }
}
