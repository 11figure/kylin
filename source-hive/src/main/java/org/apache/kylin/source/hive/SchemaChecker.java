package org.apache.kylin.source.hive;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.metadata.MetadataManager;
import org.apache.kylin.metadata.datatype.DataType;
import org.apache.kylin.metadata.model.ColumnDesc;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TblColRef;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class SchemaChecker {
    private final HiveClient hiveClient;
    private final MetadataManager metadataManager;
    private final CubeManager cubeManager;

    static class CheckResult {
        private final boolean valid;
        private final String reason;

        private CheckResult(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        void raiseExceptionWhenInvalid() {
            if (!valid) {
                throw new RuntimeException(reason);
            }
        }

        static CheckResult validOnFirstLoad(String tableName) {
            return new CheckResult(true, format("Table '%s' hasn't been loaded before", tableName));
        }

        static CheckResult validOnCompatibleSchema(String tableName) {
            return new CheckResult(true, format("Table '%s' is compatible with all existing cubes", tableName));
        }

        static CheckResult invalidOnFetchSchema(String tableName, Exception e) {
            return new CheckResult(false, format("Failed to fetch metadata of '%s': %s", tableName, e.getMessage()));
        }

        static CheckResult invalidOnIncompatibleSchema(String tableName, List<String> reasons) {
            StringBuilder buf = new StringBuilder();
            for (String reason : reasons) {
                buf.append("- ").append(reason).append("\n");
            }

            return new CheckResult(false, format("Found %d issue(s) with '%s':\n%s Please disable and purge related cube(s) first", reasons.size(), tableName, buf.toString()));
        }
    }

    SchemaChecker(HiveClient hiveClient, MetadataManager metadataManager, CubeManager cubeManager) {
        this.hiveClient = checkNotNull(hiveClient, "hiveClient is null");
        this.metadataManager = checkNotNull(metadataManager, "metadataManager is null");
        this.cubeManager = checkNotNull(cubeManager, "cubeManager is null");
    }

    private List<FieldSchema> fetchSchema(String dbName, String tblName) throws Exception {
        List<FieldSchema> fields = Lists.newArrayList();
        fields.addAll(hiveClient.getHiveTableFields(dbName, tblName));

        Table table = hiveClient.getHiveTable(dbName, tblName);
        List<FieldSchema> partitionFields = table.getPartitionKeys();
        if (partitionFields != null) {
            fields.addAll(partitionFields);
        }

        return fields;
    }

    private List<CubeInstance> findCubeByTable(final String fullTableName) {
        Iterable<CubeInstance> relatedCubes = Iterables.filter(cubeManager.listAllCubes(), new Predicate<CubeInstance>() {
            @Override
            public boolean apply(@Nullable CubeInstance cube) {
                if (cube == null || cube.allowBrokenDescriptor()) {
                    return false;
                }
                CubeDesc desc = cube.getDescriptor();

                Set<String> usedTables = Sets.newHashSet();
                usedTables.add(desc.getFactTableDesc().getIdentity());
                for (TableDesc lookup : desc.getLookupTableDescs()) {
                    usedTables.add(lookup.getIdentity());
                }

                return usedTables.contains(fullTableName);
            }
        });

        return ImmutableList.copyOf(relatedCubes);
    }

    private boolean isColumnCompatible(ColumnDesc column, FieldSchema field) {
        if (!column.getName().equalsIgnoreCase(field.getName())) {
            return false;
        }

        String typeStr = field.getType();
        // kylin uses double internally for float, see HiveSourceTableLoader.java
        // TODO should this normalization to be in DataType class ?
        if ("float".equalsIgnoreCase(typeStr)) {
            typeStr = "double";
        }
        DataType fieldType = DataType.getType(typeStr);

        if (column.getType().isIntegerFamily()) {
            // OLAPTable.listSourceColumns converts some integer columns to bigint,
            // therefore strict type comparison won't work.
            // changing from one integer type to another should be fine.
            return fieldType.isIntegerFamily();
        } else {
            // only compare base type name, changing precision or scale should be fine
            return column.getTypeName().equals(fieldType.getName());
        }
    }

    /**
     * check whether all columns used in `cube` has compatible schema in current hive schema denoted by `fieldsMap`.
     * @param cube cube to check, must use `table` in its model
     * @param table kylin's table metadata
     * @param fieldsMap current hive schema of `table`
     * @return true if all columns used in `cube` has compatible schema with `fieldsMap`, false otherwise
     */
    private List<String> checkAllColumnsInCube(CubeInstance cube, TableDesc table, Map<String, FieldSchema> fieldsMap) {
        Set<ColumnDesc> usedColumns = Sets.newHashSet();
        for (TblColRef col : cube.getAllColumns()) {
            usedColumns.add(col.getColumnDesc());
        }

        List<String> violateColumns = Lists.newArrayList();
        for (ColumnDesc column : table.getColumns()) {
            if (usedColumns.contains(column)) {
                FieldSchema field = fieldsMap.get(column.getName());
                if (field == null || !isColumnCompatible(column, field)) {
                    violateColumns.add(column.getName());
                }
            }
        }
        return violateColumns;
    }

    /**
     * check whether all columns in `table` are still in `fields` and have the same index as before.
     *
     * @param table kylin's table metadata
     * @param fields current table metadata in hive
     * @return true if only new columns are appended in hive, false otherwise
     */
    private boolean checkAllColumnsInTableDesc(TableDesc table, List<FieldSchema> fields) {
        if (table.getColumnCount() > fields.size()) {
            return false;
        }

        ColumnDesc[] columns = table.getColumns();
        for (int i = 0; i < columns.length; i++) {
            if (!isColumnCompatible(columns[i], fields.get(i))) {
                return false;
            }
        }
        return true;
    }

    public CheckResult allowReload(String dbName, String tblName) {
        final String fullTableName = (dbName + "." + tblName).toUpperCase();

        TableDesc existing = metadataManager.getTableDesc(fullTableName);
        if (existing == null) {
            return CheckResult.validOnFirstLoad(fullTableName);
        }

        List<FieldSchema> currentFields;
        Map<String, FieldSchema> currentFieldsMap = Maps.newHashMap();
        try {
            currentFields = fetchSchema(dbName, tblName);
        } catch (Exception e) {
            return CheckResult.invalidOnFetchSchema(fullTableName, e);
        }
        for (FieldSchema field : currentFields) {
            currentFieldsMap.put(field.getName().toUpperCase(), field);
        }

        List<String> issues = Lists.newArrayList();
        for (CubeInstance cube : findCubeByTable(fullTableName)) {
            TableDesc factTable = cube.getFactTableDesc();
            List<TableDesc> lookupTables = cube.getDescriptor().getLookupTableDescs();
            String modelName = cube.getDataModelDesc().getName();

            // if user reloads a fact table used by cube, then all used columns
            // must match current schema
            if (factTable.getIdentity().equals(fullTableName)) {
                List<String> violateColumns = checkAllColumnsInCube(cube, factTable, currentFieldsMap);
                if (!violateColumns.isEmpty()) {
                    issues.add(format("Column %s used in cube[%s] and model[%s], but changed in hive", violateColumns, cube.getName(), modelName));
                }
            }

            // if user reloads a lookup table used by cube, only append column(s) are allowed, all existing columns
            // must be the same (except compatible type changes)
            for (TableDesc lookupTable : lookupTables) {
                if (lookupTable.getIdentity().equals(fullTableName) && !checkAllColumnsInTableDesc(lookupTable, currentFields)) {
                    issues.add(format("Table '%s' is used as Lookup Table in cube[%s] and model[%s], but changed in hive", lookupTable.getIdentity(), cube.getName(), modelName));
                }
            }
        }

        if (issues.isEmpty()) {
            return CheckResult.validOnCompatibleSchema(fullTableName);
        }
        return CheckResult.invalidOnIncompatibleSchema(fullTableName, issues);
    }
}
