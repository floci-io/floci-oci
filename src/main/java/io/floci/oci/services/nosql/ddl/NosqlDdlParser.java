package io.floci.oci.services.nosql.ddl;

import io.floci.oci.services.nosql.query.NosqlSyntaxException;
import io.floci.oci.services.nosql.query.NosqlTokenizer.Kind;
import io.floci.oci.services.nosql.query.NosqlTokenizer.Token;
import io.floci.oci.services.nosql.query.NosqlTokens;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses the NoSQL table DDL subset: CREATE TABLE, ALTER TABLE, CREATE INDEX, DROP TABLE and
 * DROP INDEX, following the forms in Oracle's NoSQL Cloud Service reference. A trailing
 * semicolon is a syntax error, as the reference states for the console.
 */
public final class NosqlDdlParser {

    /** Types a primary key column may not have. */
    private static final Set<String> NON_KEY_TYPES = Set.of("JSON", "ARRAY", "MAP", "RECORD", "BINARY");
    private static final Set<String> IDENTITY_TYPES = Set.of("INTEGER", "LONG", "NUMBER");
    private static final Set<String> SCALAR_TYPES = Set.of("INTEGER", "LONG", "FLOAT", "DOUBLE", "NUMBER",
            "BOOLEAN", "JSON", "STRING", "BINARY", "TIMESTAMP");

    public sealed interface DdlStatement permits CreateTable, AlterTable, CreateIndex, DropTable, DropIndex {
        String tableName();
    }

    /**
     * A parsed column type. {@code text} is the canonical DDL spelling and {@code base} its first
     * word; {@code element} is the item type of an ARRAY or MAP, {@code fields} the fields of a
     * RECORD and {@code symbols} the values of an ENUM.
     */
    public record ColumnType(String text, String base, ColumnType element, List<RecordField> fields,
                             List<String> symbols) {

        static ColumnType simple(String text, String base) {
            return new ColumnType(text, base, null, List.of(), List.of());
        }
    }

    public record RecordField(String name, ColumnType type) {
    }

    public record IdentityDef(boolean always, boolean onNull, long start, long increment) {
    }

    public record ColumnDef(String name, String type, String baseType, boolean nullable, String defaultValue,
                            boolean asUuid, boolean uuidGenerated, IdentityDef identity) {
    }

    public record Ttl(long value, String unit) {

        /** Schema.ttl is in days; an hour-based TTL is reported rounded up to whole days. */
        public int days() {
            return (int) ("HOURS".equals(unit) ? (value + 23) / 24 : value);
        }

        public long millis() {
            return value * ("HOURS".equals(unit) ? 3_600_000L : 86_400_000L);
        }
    }

    public record CreateTable(String tableName, boolean ifNotExists, List<ColumnDef> columns,
                              List<String> primaryKey, List<String> shardKey, Ttl ttl) implements DdlStatement {
    }

    public record AlterTable(String tableName, List<ColumnDef> added, List<String> dropped, Ttl ttl)
            implements DdlStatement {
    }

    public record IndexKeyDef(String columnName, String jsonPath, String jsonFieldType) {
    }

    public record CreateIndex(String indexName, String tableName, boolean ifNotExists, List<IndexKeyDef> keys)
            implements DdlStatement {
    }

    public record DropTable(String tableName, boolean ifExists) implements DdlStatement {
    }

    public record DropIndex(String indexName, String tableName, boolean ifExists) implements DdlStatement {
    }

    private final NosqlTokens tokens;

    private NosqlDdlParser(String ddl) {
        this.tokens = new NosqlTokens(ddl);
    }

    public static DdlStatement parse(String ddl) {
        return new NosqlDdlParser(ddl).statement();
    }

    /** Parses a column type in its canonical spelling, as {@link ColumnDef#type()} holds it. */
    public static ColumnType parseType(String type) {
        NosqlDdlParser parser = new NosqlDdlParser(type);
        ColumnType parsed = parser.type();
        parser.tokens.expectEnd();
        return parsed;
    }

    private DdlStatement statement() {
        DdlStatement statement;
        if (tokens.accept("CREATE")) {
            if (tokens.accept("TABLE")) {
                statement = createTable();
            } else if (tokens.accept("INDEX")) {
                statement = createIndex();
            } else {
                throw tokens.error("Expected TABLE or INDEX after CREATE");
            }
        } else if (tokens.accept("ALTER")) {
            tokens.expect("TABLE");
            statement = alterTable();
        } else if (tokens.accept("DROP")) {
            if (tokens.accept("TABLE")) {
                boolean ifExists = ifExists();
                statement = new DropTable(tableName(), ifExists);
            } else if (tokens.accept("INDEX")) {
                boolean ifExists = ifExists();
                String index = tokens.identifier("an index name");
                tokens.expect("ON");
                statement = new DropIndex(index, tableName(), ifExists);
            } else {
                throw tokens.error("Expected TABLE or INDEX after DROP");
            }
        } else {
            throw tokens.error("Expected a CREATE, ALTER or DROP statement");
        }
        tokens.expectEnd();
        return statement;
    }

    private boolean ifExists() {
        if (tokens.accept("IF")) {
            tokens.expect("EXISTS");
            return true;
        }
        return false;
    }

    private boolean ifNotExists() {
        if (tokens.accept("IF")) {
            tokens.expect("NOT");
            tokens.expect("EXISTS");
            return true;
        }
        return false;
    }

    private String tableName() {
        StringBuilder name = new StringBuilder(tokens.identifier("a table name"));
        while (tokens.acceptSymbol(".")) {
            name.append('.').append(tokens.identifier("a child table name"));
        }
        return name.toString();
    }

    private CreateTable createTable() {
        boolean ifNotExists = ifNotExists();
        String table = tableName();
        tokens.expectSymbol("(");
        List<ColumnDef> columns = new ArrayList<>();
        List<String> primaryKey = null;
        List<String> shardKey = null;
        do {
            if (tokens.peek().is("PRIMARY") && tokens.peek(1).is("KEY")) {
                if (primaryKey != null) {
                    throw tokens.error("Duplicate PRIMARY KEY clause");
                }
                tokens.next();
                tokens.next();
                tokens.expectSymbol("(");
                primaryKey = new ArrayList<>();
                shardKey = new ArrayList<>();
                if (tokens.accept("SHARD")) {
                    tokens.expectSymbol("(");
                    shardKey.addAll(identifierList("a shard key column"));
                    tokens.expectSymbol(")");
                    primaryKey.addAll(shardKey);
                    if (tokens.acceptSymbol(",")) {
                        primaryKey.addAll(identifierList("a primary key column"));
                    }
                } else {
                    primaryKey.addAll(identifierList("a primary key column"));
                }
                tokens.expectSymbol(")");
            } else {
                columns.add(columnDefinition());
            }
        } while (tokens.acceptSymbol(","));
        tokens.expectSymbol(")");
        Ttl ttl = null;
        if (tokens.accept("USING")) {
            ttl = ttl();
        }
        if (primaryKey == null) {
            throw new NosqlSyntaxException("CREATE TABLE " + table + " has no PRIMARY KEY");
        }
        if (shardKey.isEmpty()) {
            shardKey = List.copyOf(primaryKey);
        }
        validateTable(table, columns, primaryKey);
        List<String> keyColumns = primaryKey;
        List<ColumnDef> keyed = columns.stream()
                .map(c -> containsIgnoreCase(keyColumns, c.name()) && c.nullable()
                        ? new ColumnDef(c.name(), c.type(), c.baseType(), false, c.defaultValue(),
                                c.asUuid(), c.uuidGenerated(), c.identity())
                        : c)
                .toList();
        return new CreateTable(table, ifNotExists, keyed, canonicalNames(primaryKey, columns),
                canonicalNames(shardKey, columns), ttl);
    }

    private static void validateTable(String table, List<ColumnDef> columns, List<String> primaryKey) {
        Set<String> seen = new LinkedHashSet<>();
        int identities = 0;
        for (ColumnDef column : columns) {
            if (!seen.add(column.name().toLowerCase(Locale.ROOT))) {
                throw new NosqlSyntaxException("Duplicate column " + column.name() + " in table " + table);
            }
            if (column.identity() != null) {
                identities++;
            }
        }
        if (identities > 1) {
            throw new NosqlSyntaxException("Table " + table + " declares more than one identity column");
        }
        Set<String> keySeen = new LinkedHashSet<>();
        for (String key : primaryKey) {
            ColumnDef column = columns.stream().filter(c -> c.name().equalsIgnoreCase(key)).findFirst()
                    .orElseThrow(() -> new NosqlSyntaxException(
                            "Primary key column " + key + " is not a column of table " + table));
            if (!keySeen.add(key.toLowerCase(Locale.ROOT))) {
                throw new NosqlSyntaxException("Duplicate primary key column " + key);
            }
            if (NON_KEY_TYPES.contains(column.baseType())) {
                throw new NosqlSyntaxException("Column " + column.name() + " of type " + column.type()
                        + " cannot be part of a primary key");
            }
        }
    }

    private static List<String> canonicalNames(List<String> names, List<ColumnDef> columns) {
        return names.stream()
                .map(n -> columns.stream().filter(c -> c.name().equalsIgnoreCase(n)).findFirst()
                        .map(ColumnDef::name).orElse(n))
                .toList();
    }

    private static boolean containsIgnoreCase(List<String> names, String name) {
        return names.stream().anyMatch(n -> n.equalsIgnoreCase(name));
    }

    private List<String> identifierList(String what) {
        List<String> names = new ArrayList<>();
        do {
            names.add(tokens.identifier(what));
        } while (tokens.acceptSymbol(","));
        return names;
    }

    private Ttl ttl() {
        tokens.expect("TTL");
        long value = tokens.integer("a TTL value");
        String unit;
        if (tokens.accept("DAYS") || tokens.accept("DAY")) {
            unit = "DAYS";
        } else if (tokens.accept("HOURS") || tokens.accept("HOUR")) {
            unit = "HOURS";
        } else {
            throw tokens.error("Expected DAYS or HOURS");
        }
        return new Ttl(value, unit);
    }

    private ColumnDef columnDefinition() {
        String name = tokens.identifier("a column name");
        ColumnType type = type();
        boolean asUuid = false;
        boolean uuidGenerated = false;
        if ("STRING".equals(type.base()) && tokens.peek().is("AS") && tokens.peek(1).is("UUID")) {
            tokens.next();
            tokens.next();
            asUuid = true;
            if (tokens.peek().is("GENERATED") && tokens.peek(1).is("BY")) {
                tokens.next();
                tokens.next();
                tokens.expect("DEFAULT");
                uuidGenerated = true;
            }
        }
        boolean notNull = false;
        String defaultValue = null;
        IdentityDef identity = null;
        while (true) {
            if (tokens.accept("NOT")) {
                tokens.expect("NULL");
                notNull = true;
            } else if (tokens.accept("DEFAULT")) {
                defaultValue = literal();
            } else if (tokens.accept("COMMENT")) {
                if (tokens.next().kind() != Kind.STRING) {
                    throw tokens.error("Expected a comment string");
                }
            } else if (tokens.peek().is("GENERATED")) {
                identity = identity(name, type);
            } else {
                break;
            }
        }
        if (notNull && defaultValue == null && identity == null) {
            throw new NosqlSyntaxException("Column " + name + " is NOT NULL but has no DEFAULT value");
        }
        return new ColumnDef(name, type.text(), type.base(), !notNull, defaultValue, asUuid, uuidGenerated,
                identity);
    }

    private IdentityDef identity(String column, ColumnType type) {
        tokens.expect("GENERATED");
        boolean always;
        boolean onNull = false;
        if (tokens.accept("ALWAYS")) {
            always = true;
        } else {
            tokens.expect("BY");
            tokens.expect("DEFAULT");
            always = false;
            if (tokens.accept("ON")) {
                tokens.expect("NULL");
                onNull = true;
            }
        }
        tokens.expect("AS");
        tokens.expect("IDENTITY");
        if (!IDENTITY_TYPES.contains(type.base())) {
            throw new NosqlSyntaxException("Identity column " + column + " must be INTEGER, LONG or NUMBER");
        }
        long start = 1;
        long increment = 1;
        if (tokens.acceptSymbol("(")) {
            while (!tokens.acceptSymbol(")")) {
                if (tokens.atEnd()) {
                    throw tokens.error("Expected ')' closing the identity options");
                }
                if (tokens.accept("START")) {
                    tokens.expect("WITH");
                    start = signedInteger("a START WITH value");
                } else if (tokens.accept("INCREMENT")) {
                    tokens.expect("BY");
                    increment = signedInteger("an INCREMENT BY value");
                    if (increment == 0) {
                        throw new NosqlSyntaxException("INCREMENT BY must not be 0");
                    }
                } else {
                    tokens.next();
                }
            }
        }
        return new IdentityDef(always, onNull, start, increment);
    }

    private long signedInteger(String what) {
        boolean negative = tokens.acceptSymbol("-");
        long value = tokens.integer(what);
        return negative ? -value : value;
    }

    private String literal() {
        Token token = tokens.next();
        return switch (token.kind()) {
            case STRING -> token.text();
            case NUMBER -> token.text();
            case IDENT -> {
                if (token.is("TRUE") || token.is("FALSE")) {
                    yield token.text().toLowerCase(Locale.ROOT);
                }
                throw new NosqlSyntaxException("Expected a DEFAULT literal at position " + token.position());
            }
            case SYMBOL -> {
                if (token.isSymbol("-") && tokens.peek().kind() == Kind.NUMBER) {
                    yield "-" + tokens.next().text();
                }
                throw new NosqlSyntaxException("Expected a DEFAULT literal at position " + token.position());
            }
            default -> throw new NosqlSyntaxException("Expected a DEFAULT literal at position " + token.position());
        };
    }

    private ColumnType type() {
        Token token = tokens.peek();
        if (token.kind() != Kind.IDENT) {
            throw tokens.error("Expected a column type");
        }
        String base = token.upper();
        tokens.next();
        return switch (base) {
            case "INTEGER", "LONG", "FLOAT", "DOUBLE", "NUMBER", "BOOLEAN", "JSON", "STRING" ->
                    ColumnType.simple(base, base);
            case "BINARY", "TIMESTAMP" -> {
                if (tokens.acceptSymbol("(")) {
                    long size = tokens.integer("a size or precision");
                    tokens.expectSymbol(")");
                    yield ColumnType.simple(base + "(" + size + ")", base);
                }
                yield ColumnType.simple(base, base);
            }
            case "ENUM" -> {
                tokens.expectSymbol("(");
                List<String> values = new ArrayList<>();
                do {
                    values.add(tokens.identifier("an ENUM value"));
                } while (tokens.acceptSymbol(","));
                tokens.expectSymbol(")");
                yield new ColumnType("ENUM(" + String.join(", ", values) + ")", base, null, List.of(),
                        List.copyOf(values));
            }
            case "ARRAY", "MAP" -> {
                tokens.expectSymbol("(");
                ColumnType element = type();
                tokens.expectSymbol(")");
                yield new ColumnType(base + "(" + element.text() + ")", base, element, List.of(), List.of());
            }
            case "RECORD" -> {
                tokens.expectSymbol("(");
                List<RecordField> fields = new ArrayList<>();
                do {
                    String field = tokens.identifier("a RECORD field name");
                    fields.add(new RecordField(field, type()));
                } while (tokens.acceptSymbol(","));
                tokens.expectSymbol(")");
                String text = String.join(", ", fields.stream().map(f -> f.name() + " " + f.type().text()).toList());
                yield new ColumnType("RECORD(" + text + ")", base, null, List.copyOf(fields), List.of());
            }
            default -> throw new NosqlSyntaxException("Unknown column type " + token.text()
                    + " at position " + token.position() + "; expected one of " + SCALAR_TYPES
                    + ", ENUM, ARRAY, MAP or RECORD");
        };
    }

    private AlterTable alterTable() {
        String table = tableName();
        List<ColumnDef> added = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        Ttl ttl = null;
        if (tokens.acceptSymbol("(")) {
            do {
                if (tokens.accept("ADD")) {
                    ColumnDef column = columnDefinition();
                    if (column.identity() != null) {
                        throw new NosqlSyntaxException("ALTER TABLE cannot add the identity column " + column.name());
                    }
                    added.add(column);
                } else if (tokens.accept("DROP")) {
                    dropped.add(tokens.identifier("a column name"));
                } else {
                    throw tokens.error("Expected ADD or DROP");
                }
            } while (tokens.acceptSymbol(","));
            tokens.expectSymbol(")");
        } else if (tokens.accept("USING")) {
            ttl = ttl();
        } else {
            throw tokens.error("Expected '(' or USING TTL");
        }
        return new AlterTable(table, added, dropped, ttl);
    }

    private CreateIndex createIndex() {
        boolean ifNotExists = ifNotExists();
        String index = tokens.identifier("an index name");
        tokens.expect("ON");
        String table = tableName();
        tokens.expectSymbol("(");
        List<IndexKeyDef> keys = new ArrayList<>();
        do {
            String column = tokens.identifier("an index column");
            List<String> path = new ArrayList<>();
            while (tokens.acceptSymbol(".")) {
                Token step = tokens.next();
                if (step.kind() != Kind.IDENT && step.kind() != Kind.STRING) {
                    throw new NosqlSyntaxException("Expected a JSON field name at position " + step.position());
                }
                path.add(step.text());
            }
            String fieldType = null;
            if (tokens.accept("AS")) {
                fieldType = tokens.identifier("an index field type").toUpperCase(Locale.ROOT);
            }
            keys.add(new IndexKeyDef(column, path.isEmpty() ? null : String.join(".", path), fieldType));
        } while (tokens.acceptSymbol(","));
        tokens.expectSymbol(")");
        return new CreateIndex(index, table, ifNotExists, keys);
    }
}
