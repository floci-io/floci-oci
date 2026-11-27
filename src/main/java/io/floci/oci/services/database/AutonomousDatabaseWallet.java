package io.floci.oci.services.database;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The client side of an Autonomous Database: its connection strings and its wallet. Both follow
 * the Autonomous Database Free container (local/oracle/oci-docs/adb-free-spike.md): five mTLS
 * aliases, {@code <db>_high}, {@code _medium}, {@code _low}, {@code _tp} and {@code _tpurgent},
 * over TCPS with service names {@code <db>_<level>.adb.oraclecloud.com} and a match on the
 * server certificate's DN.
 */
final class AutonomousDatabaseWallet {

    /** Consumer group to alias suffix, in the order the container's tnsnames.ora lists them. */
    private static final List<String> LEVELS = List.of("HIGH", "MEDIUM", "LOW", "TP", "TPURGENT");
    private static final Pattern SERVICE_NAME = Pattern.compile("(?i)\\(\\s*service_name\\s*=\\s*([^)\\s]+)\\s*\\)");
    private static final Pattern HOST = Pattern.compile("(?i)\\(\\s*host\\s*=\\s*[^)]*\\)");
    private static final Pattern PORT = Pattern.compile("(?i)\\(\\s*port\\s*=\\s*\\d+\\s*\\)");

    private AutonomousDatabaseWallet() {
    }

    /** The descriptor tnsnames.ora and the connection-string profiles carry for one alias. */
    static String descriptor(String dbName, String level, String host, int port) {
        return "(description= (retry_count=20)(retry_delay=3)(address=(protocol=tcps)(port=" + port
                + ")(host=" + host + "))(connect_data=(service_name=" + serviceName(dbName, level)
                + "))(security=(ssl_server_dn_match=yes)))";
    }

    private static String serviceName(String dbName, String level) {
        return dbName.toLowerCase(Locale.ROOT) + "_" + level.toLowerCase(Locale.ROOT) + ".adb.oraclecloud.com";
    }

    /** AutonomousDatabaseConnectionStrings for a mock database: the aliases its dbName predicts. */
    static Map<String, Object> connectionStrings(String dbName, String host, int port) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String level : LEVELS) {
            aliases.put(alias(dbName, level), descriptor(dbName, level, host, port));
        }
        return connectionStrings(aliases, host, port);
    }

    /**
     * AutonomousDatabaseConnectionStrings from tnsnames.ora entries already pointing at
     * {@code host} and {@code port}: high, medium and low, every consumer group, and one mTLS
     * profile per alias. Aliases without a consumer-group suffix are left out.
     */
    static Map<String, Object> connectionStrings(Map<String, String> aliases, String host, int port) {
        Map<String, String> all = new LinkedHashMap<>();
        List<Map<String, Object>> profiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            String level = level(entry.getKey());
            String serviceName = serviceName(entry.getValue());
            if (level == null || serviceName == null) {
                continue;
            }
            all.put(level, host + ":" + port + "/" + serviceName);
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("displayName", entry.getKey());
            profile.put("value", entry.getValue());
            profile.put("protocol", "TCPS");
            profile.put("hostFormat", "FQDN");
            profile.put("sessionMode", "DIRECT");
            profile.put("syntaxFormat", "LONG");
            profile.put("consumerGroup", level);
            profile.put("tlsAuthentication", "MUTUAL");
            profile.put("isRegional", false);
            profiles.add(profile);
        }
        Map<String, Object> strings = new LinkedHashMap<>();
        strings.put("high", all.get("HIGH"));
        strings.put("medium", all.get("MEDIUM"));
        strings.put("low", all.get("LOW"));
        strings.put("allConnectionStrings", all);
        strings.put("profiles", profiles);
        return strings;
    }

    /** The consumer group an alias such as {@code orders_tpurgent} names, or null. */
    private static String level(String alias) {
        int underscore = alias.lastIndexOf('_');
        if (underscore < 0) {
            return null;
        }
        String level = alias.substring(underscore + 1).toUpperCase(Locale.ROOT);
        return LEVELS.contains(level) ? level : null;
    }

    private static String serviceName(String descriptor) {
        Matcher matcher = SERVICE_NAME.matcher(descriptor);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * The entries of a tnsnames.ora file, alias to descriptor, in file order, with each
     * descriptor on one line. Comments are skipped.
     */
    static Map<String, String> parseTnsnames(String tnsnames) {
        Map<String, String> entries = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        for (String line : tnsnames.split("\\R")) {
            if (!line.strip().startsWith("#")) {
                text.append(line).append('\n');
            }
        }
        int i = 0;
        while (i < text.length()) {
            int equals = text.indexOf("=", i);
            int open = equals < 0 ? -1 : text.indexOf("(", equals);
            if (open < 0) {
                break;
            }
            String alias = text.substring(i, equals).strip();
            int depth = 0;
            int end = open;
            for (; end < text.length(); end++) {
                char c = text.charAt(end);
                if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    break;
                }
            }
            if (depth != 0) {
                break;
            }
            if (!alias.isEmpty()) {
                entries.put(alias, text.substring(open, end + 1).replaceAll("\\s+", " ")
                        .replace("( ", "(").replace(" )", ")").replace(") (", ")("));
            }
            i = end + 1;
        }
        return entries;
    }

    /** A descriptor whose every address names {@code host} and {@code port}. */
    static String rewriteAddress(String descriptor, String host, int port) {
        String rewritten = HOST.matcher(descriptor).replaceAll(Matcher.quoteReplacement("(host=" + host + ")"));
        return PORT.matcher(rewritten).replaceAll("(port=" + port + ")");
    }

    /**
     * The container's mTLS entries, pointing at {@code host} and {@code port}. The {@code _tls}
     * entries are left out: they address the container's 1521 listener, which is not published.
     */
    static Map<String, String> mtlsEntries(String tnsnames, String host, int port) {
        Map<String, String> entries = new LinkedHashMap<>();
        parseTnsnames(tnsnames).forEach((alias, descriptor) -> {
            if (!alias.toLowerCase(Locale.ROOT).endsWith("_tls")) {
                entries.put(alias, rewriteAddress(descriptor, host, port));
            }
        });
        return entries;
    }

    private static String tnsnamesFile(Map<String, String> entries) {
        StringBuilder tnsnames = new StringBuilder();
        entries.forEach((alias, descriptor) -> tnsnames.append(alias).append(" = ").append(descriptor)
                .append("\n\n"));
        return tnsnames.toString();
    }

    private static String alias(String dbName, String level) {
        return dbName.toLowerCase(Locale.ROOT) + "_" + level.toLowerCase(Locale.ROOT);
    }

    /**
     * A wallet zip with tnsnames.ora, sqlnet.ora, ojdbc.properties and a README. A mock database
     * has no server, so the wallet carries no keystore: it names the aliases a client would use.
     */
    static byte[] zip(String dbName, String host, int port) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String level : LEVELS) {
            aliases.put(alias(dbName, level), descriptor(dbName, level, host, port));
        }
        Map<String, String> files = new LinkedHashMap<>();
        files.put("tnsnames.ora", tnsnamesFile(aliases));
        files.put("sqlnet.ora", "WALLET_LOCATION = (SOURCE = (METHOD = file) (METHOD_DATA = (DIRECTORY=\"?/network/admin\")))\n"
                + "SSL_SERVER_DN_MATCH=yes\n");
        files.put("ojdbc.properties", "oracle.net.wallet_location=(source=(method=file)(method_data=(directory=${TNS_ADMIN})))\n");
        files.put("README", "Wallet generated by floci-oci for Autonomous Database " + dbName + ".\n"
                + "floci-oci emulates this database's management plane only: nothing listens on the\n"
                + "addresses in tnsnames.ora, and the wallet holds no keystore.\n");
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((name, content) -> bytes.put(name, content.getBytes(StandardCharsets.UTF_8)));
        return zip(bytes);
    }

    /**
     * The wallet of a real database: the container's {@code tls_wallet} files, with
     * {@code tnsnames.ora} pointing at {@code host} and {@code port}, and the keystores moved from
     * the container's wallet password to {@code password}. {@code cwallet.sso}, the auto-login
     * wallet, needs no password and is passed through, as are the other files, except the
     * container's own lock and state files ({@code *.lck}, dot files).
     *
     * @throws IllegalStateException when a keystore cannot be re-encrypted
     */
    static byte[] zip(Map<String, byte[]> walletFiles, char[] containerPassword, char[] password,
                      String host, int port) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> file : walletFiles.entrySet()) {
            String name = file.getKey();
            if (name.endsWith(".lck") || name.startsWith(".")) {
                continue;
            }
            byte[] content = file.getValue();
            try {
                files.put(name, switch (name) {
                    case "tnsnames.ora" -> tnsnamesFile(mtlsEntries(new String(content, StandardCharsets.UTF_8),
                            host, port)).getBytes(StandardCharsets.UTF_8);
                    case "ewallet.p12" -> WalletKeystores.reencryptPkcs12(content, containerPassword, password);
                    case "ewallet.pem" -> WalletKeystores.reencryptPem(content, containerPassword, password);
                    case "keystore.jks", "truststore.jks" ->
                            WalletKeystores.reencryptJks(content, containerPassword, password);
                    default -> content;
                });
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Could not re-encrypt " + name + " of the wallet: " + e.getMessage(), e);
            }
        }
        return zip(files);
    }

    private static byte[] zip(Map<String, byte[]> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the wallet zip", e);
        }
        return out.toByteArray();
    }
}
