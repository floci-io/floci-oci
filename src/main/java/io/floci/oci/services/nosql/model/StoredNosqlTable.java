package io.floci.oci.services.nosql.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A NoSQL table with its parsed schema and its indexes. {@code ddlStatement} is the last DDL
 * statement the table received, kept verbatim: Terraform reads it back and diffs it against
 * the configuration. {@code identityNext} and {@code versionSequence} are emulator counters,
 * never on the wire.
 */
@RegisterForReflection
public class StoredNosqlTable {

    private String id;
    private String compartmentId;
    private String name;
    private String timeCreated;
    private String timeUpdated;
    private String lifecycleState;
    private String ddlStatement;
    private String schemaState = "MUTABLE";
    private boolean autoReclaimable;
    private Limits tableLimits;
    private List<Column> columns = new ArrayList<>();
    private List<String> primaryKey = new ArrayList<>();
    private List<String> shardKey = new ArrayList<>();
    private Long ttlValue;
    private String ttlUnit;
    private Identity identity;
    private long identityNext;
    private long versionSequence;
    private List<Index> indexes = new ArrayList<>();
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String etag;

    public StoredNosqlTable() {
    }

    public Column column(String columnName) {
        return columns.stream().filter(c -> c.getName().equalsIgnoreCase(columnName)).findFirst().orElse(null);
    }

    public Index index(String indexName) {
        return indexes.stream().filter(i -> i.getName().equalsIgnoreCase(indexName)).findFirst().orElse(null);
    }

    @RegisterForReflection
    public static class Limits {
        private int maxReadUnits;
        private int maxWriteUnits;
        private int maxStorageInGBs;
        private String capacityMode;

        public Limits() {
        }

        public Limits(int maxReadUnits, int maxWriteUnits, int maxStorageInGBs, String capacityMode) {
            this.maxReadUnits = maxReadUnits;
            this.maxWriteUnits = maxWriteUnits;
            this.maxStorageInGBs = maxStorageInGBs;
            this.capacityMode = capacityMode;
        }

        public int getMaxReadUnits() {
            return maxReadUnits;
        }

        public void setMaxReadUnits(int maxReadUnits) {
            this.maxReadUnits = maxReadUnits;
        }

        public int getMaxWriteUnits() {
            return maxWriteUnits;
        }

        public void setMaxWriteUnits(int maxWriteUnits) {
            this.maxWriteUnits = maxWriteUnits;
        }

        public int getMaxStorageInGBs() {
            return maxStorageInGBs;
        }

        public void setMaxStorageInGBs(int maxStorageInGBs) {
            this.maxStorageInGBs = maxStorageInGBs;
        }

        public String getCapacityMode() {
            return capacityMode;
        }

        public void setCapacityMode(String capacityMode) {
            this.capacityMode = capacityMode;
        }
    }

    /** A column; {@code type} is the canonical DDL spelling, {@code baseType} its first word. */
    @RegisterForReflection
    public static class Column {
        private String name;
        private String type;
        private String baseType;
        private boolean nullable = true;
        private String defaultValue;
        private boolean asUuid;
        private boolean uuidGenerated;

        public Column() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getBaseType() {
            return baseType;
        }

        public void setBaseType(String baseType) {
            this.baseType = baseType;
        }

        public boolean isNullable() {
            return nullable;
        }

        public void setNullable(boolean nullable) {
            this.nullable = nullable;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
        }

        public boolean isAsUuid() {
            return asUuid;
        }

        public void setAsUuid(boolean asUuid) {
            this.asUuid = asUuid;
        }

        public boolean isUuidGenerated() {
            return uuidGenerated;
        }

        public void setUuidGenerated(boolean uuidGenerated) {
            this.uuidGenerated = uuidGenerated;
        }
    }

    @RegisterForReflection
    public static class Identity {
        private String columnName;
        private boolean always;
        private boolean onNull;
        private long start = 1;
        private long increment = 1;

        public Identity() {
        }

        public String getColumnName() {
            return columnName;
        }

        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }

        public boolean isAlways() {
            return always;
        }

        public void setAlways(boolean always) {
            this.always = always;
        }

        public boolean isOnNull() {
            return onNull;
        }

        public void setOnNull(boolean onNull) {
            this.onNull = onNull;
        }

        public long getStart() {
            return start;
        }

        public void setStart(long start) {
            this.start = start;
        }

        public long getIncrement() {
            return increment;
        }

        public void setIncrement(long increment) {
            this.increment = increment;
        }
    }

    @RegisterForReflection
    public static class Index {
        private String name;
        private List<IndexKey> keys = new ArrayList<>();
        private String lifecycleState;
        private String timeCreated;
        private String etag;

        public Index() {
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<IndexKey> getKeys() {
            return keys;
        }

        public void setKeys(List<IndexKey> keys) {
            this.keys = keys;
        }

        public String getLifecycleState() {
            return lifecycleState;
        }

        public void setLifecycleState(String lifecycleState) {
            this.lifecycleState = lifecycleState;
        }

        public String getTimeCreated() {
            return timeCreated;
        }

        public void setTimeCreated(String timeCreated) {
            this.timeCreated = timeCreated;
        }

        public String getEtag() {
            return etag;
        }

        public void setEtag(String etag) {
            this.etag = etag;
        }
    }

    @RegisterForReflection
    public static class IndexKey {
        private String columnName;
        private String jsonPath;
        private String jsonFieldType;

        public IndexKey() {
        }

        public IndexKey(String columnName, String jsonPath, String jsonFieldType) {
            this.columnName = columnName;
            this.jsonPath = jsonPath;
            this.jsonFieldType = jsonFieldType;
        }

        public String getColumnName() {
            return columnName;
        }

        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }

        public String getJsonPath() {
            return jsonPath;
        }

        public void setJsonPath(String jsonPath) {
            this.jsonPath = jsonPath;
        }

        public String getJsonFieldType() {
            return jsonFieldType;
        }

        public void setJsonFieldType(String jsonFieldType) {
            this.jsonFieldType = jsonFieldType;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(String timeCreated) {
        this.timeCreated = timeCreated;
    }

    public String getTimeUpdated() {
        return timeUpdated;
    }

    public void setTimeUpdated(String timeUpdated) {
        this.timeUpdated = timeUpdated;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public String getDdlStatement() {
        return ddlStatement;
    }

    public void setDdlStatement(String ddlStatement) {
        this.ddlStatement = ddlStatement;
    }

    public String getSchemaState() {
        return schemaState;
    }

    public void setSchemaState(String schemaState) {
        this.schemaState = schemaState;
    }

    public boolean isAutoReclaimable() {
        return autoReclaimable;
    }

    public void setAutoReclaimable(boolean autoReclaimable) {
        this.autoReclaimable = autoReclaimable;
    }

    public Limits getTableLimits() {
        return tableLimits;
    }

    public void setTableLimits(Limits tableLimits) {
        this.tableLimits = tableLimits;
    }

    public List<Column> getColumns() {
        return columns;
    }

    public void setColumns(List<Column> columns) {
        this.columns = columns;
    }

    public List<String> getPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(List<String> primaryKey) {
        this.primaryKey = primaryKey;
    }

    public List<String> getShardKey() {
        return shardKey;
    }

    public void setShardKey(List<String> shardKey) {
        this.shardKey = shardKey;
    }

    public Long getTtlValue() {
        return ttlValue;
    }

    public void setTtlValue(Long ttlValue) {
        this.ttlValue = ttlValue;
    }

    public String getTtlUnit() {
        return ttlUnit;
    }

    public void setTtlUnit(String ttlUnit) {
        this.ttlUnit = ttlUnit;
    }

    public Identity getIdentity() {
        return identity;
    }

    public void setIdentity(Identity identity) {
        this.identity = identity;
    }

    public long getIdentityNext() {
        return identityNext;
    }

    public void setIdentityNext(long identityNext) {
        this.identityNext = identityNext;
    }

    public long getVersionSequence() {
        return versionSequence;
    }

    public void setVersionSequence(long versionSequence) {
        this.versionSequence = versionSequence;
    }

    public List<Index> getIndexes() {
        return indexes;
    }

    public void setIndexes(List<Index> indexes) {
        this.indexes = indexes;
    }

    public Map<String, String> getFreeformTags() {
        return freeformTags;
    }

    public void setFreeformTags(Map<String, String> freeformTags) {
        this.freeformTags = freeformTags;
    }

    public Map<String, Map<String, Object>> getDefinedTags() {
        return definedTags;
    }

    public void setDefinedTags(Map<String, Map<String, Object>> definedTags) {
        this.definedTags = definedTags;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }
}
