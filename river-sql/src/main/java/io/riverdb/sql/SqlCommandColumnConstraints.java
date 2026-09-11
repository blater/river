package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlDefaultKind;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.util.Arrays;

/** Command-owned column metadata; the command supplies its authoritative admitted count. */
final class SqlCommandColumnConstraints {
  private SqlIdentifier[] columnReferenceTableNames = new SqlIdentifier[8];
  private SqlIdentifier[] columnReferenceColumnNames = new SqlIdentifier[8];
  private long[] columnDefaultHighs = new long[8];
  private long[] columnDefaultValues = new long[8];
  private byte[] columnDefaultKinds = new byte[8];
  private int[] columnTypeDescriptors = new int[8];
  private long[] columnCheckHighs = new long[8];
  private long[] columnCheckValues = new long[8];
  private int[] columnCheckTypeDescriptors = new int[8];
  private SqlComparison[] columnCheckComparisons = new SqlComparison[8];
  private boolean[] columnNotNull = new boolean[8];
  private boolean[] columnDefaults = new boolean[8];
  private boolean[] columnUnique = new boolean[8];
  private boolean[] columnReferences = new boolean[8];
  private boolean primaryKeyIdentity;
  private int primaryKeyIdentityColumn = -1;

  SqlCommandColumnConstraints() {
    for (int index = 0; index < columnReferenceTableNames.length; index++) {
      columnReferenceTableNames[index] = new SqlIdentifier();
      columnReferenceColumnNames[index] = new SqlIdentifier();
    }
  }

  void markNotNull(int columnCount) {
    if (columnCount > 0) {
      columnNotNull[columnCount - 1] = true;
    }
  }

  void markIdentity(int columnCount) {
    primaryKeyIdentity = true;
    primaryKeyIdentityColumn = columnCount - 1;
  }

  void markDefault(int columnCount, long high, long value) {
    if (columnCount > 0) {
      int column = columnCount - 1;
      columnDefaults[column] = true;
      columnDefaultHighs[column] = high;
      columnDefaultValues[column] = value;
      columnDefaultKinds[column] = SqlDefaultKind.LITERAL;
    }
  }

  void markCurrentDefault(int columnCount, int kind) {
    if (columnCount > 0) {
      int column = columnCount - 1;
      columnDefaults[column] = true;
      columnDefaultHighs[column] = 0;
      columnDefaultValues[column] = 0;
      columnDefaultKinds[column] = (byte) kind;
    }
  }

  void markVarchar(int columnCount, int maximumScalars) {
    markType(columnCount, SqlTypeDescriptor.varchar(maximumScalars));
  }

  void markType(int columnCount, int descriptor) {
    if (columnCount > 0 && SqlTypeDescriptor.isValid(descriptor)) {
      columnTypeDescriptors[columnCount - 1] = descriptor;
    }
  }

  StatusCode markUnique(int columnCount) {
    if (columnCount < 1 || columnUnique[columnCount - 1]) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (count(columnUnique, columnCount) >= SqlShapeLimits.MAX_SECONDARY_INDEXES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    columnUnique[columnCount - 1] = true;
    return StatusCode.OK;
  }

  SqlIdentifier referenceTable(int columnCount) {
    return columnCount > 0
        ? columnReferenceTableNames[columnCount - 1] : null;
  }

  SqlIdentifier referenceColumn(int columnCount) {
    return columnCount > 0
        ? columnReferenceColumnNames[columnCount - 1] : null;
  }

  StatusCode markReference(int columnCount) {
    int column = columnCount - 1;
    if (column < 0 || columnReferences[column]
        || columnReferenceTableNames[column].length() == 0
        || columnReferenceColumnNames[column].length() == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (count(columnReferences, columnCount) >= SqlShapeLimits.MAX_FOREIGN_KEYS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    columnReferences[column] = true;
    return StatusCode.OK;
  }

  void markCheck(
      int columnCount,
      SqlComparison comparison,
      long high,
      long value,
      int descriptor) {
    if (columnCount > 0) {
      int column = columnCount - 1;
      columnCheckComparisons[column] = comparison;
      columnCheckHighs[column] = high;
      columnCheckValues[column] = value;
      columnCheckTypeDescriptors[column] = descriptor;
    }
  }

  boolean columnIsNotNull(int columnCount, int index) {
    return valid(columnCount, index)
        && columnNotNull[index];
  }

  boolean columnHasDefault(int columnCount, int index) {
    return valid(columnCount, index)
        && columnDefaults[index];
  }

  long columnDefaultValue(int columnCount, int index) {
    return columnHasDefault(columnCount, index) ? columnDefaultValues[index] : 0;
  }

  long columnDefaultHigh(int columnCount, int index) {
    return columnHasDefault(columnCount, index) ? columnDefaultHighs[index] : 0;
  }

  int columnDefaultKind(int columnCount, int index) {
    return columnHasDefault(columnCount, index)
        ? Byte.toUnsignedInt(columnDefaultKinds[index]) : 0;
  }

  boolean columnIsVarchar(int columnCount, int index) {
    return valid(columnCount, index)
        && SqlTypeDescriptor.typeId(columnTypeDescriptors[index])
            == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  int columnTypeDescriptor(int columnCount, int index) {
    return valid(columnCount, index) ? columnTypeDescriptors[index] : 0;
  }

  boolean columnIsUnique(int columnCount, int index) {
    return valid(columnCount, index)
        && columnUnique[index];
  }

  boolean columnHasReference(int columnCount, int index) {
    return valid(columnCount, index)
        && columnReferences[index];
  }

  SqlIdentifier columnReferenceTableName(int columnCount, int index) {
    return columnHasReference(columnCount, index) ? columnReferenceTableNames[index] : null;
  }

  SqlIdentifier columnReferenceColumnName(int columnCount, int index) {
    return columnHasReference(columnCount, index) ? columnReferenceColumnNames[index] : null;
  }

  boolean columnHasCheck(int columnCount, int index) {
    return valid(columnCount, index)
        && columnCheckComparisons[index] != null;
  }

  SqlComparison columnCheckComparison(int columnCount, int index) {
    return columnHasCheck(columnCount, index) ? columnCheckComparisons[index] : null;
  }

  long columnCheckValue(int columnCount, int index) {
    return columnHasCheck(columnCount, index) ? columnCheckValues[index] : 0;
  }

  long columnCheckHigh(int columnCount, int index) {
    return columnHasCheck(columnCount, index) ? columnCheckHighs[index] : 0;
  }

  int columnCheckTypeDescriptor(int columnCount, int index) {
    return columnHasCheck(columnCount, index) ? columnCheckTypeDescriptors[index] : 0;
  }

  private boolean valid(int columnCount, int index) {
    return index >= 0 && index < columnCount;
  }

  void prepareGrowth(SqlCommandColumnGrowth growth, int capacity) {
    growth.referenceTables = identifiers(columnReferenceTableNames, capacity);
    growth.referenceColumns = identifiers(columnReferenceColumnNames, capacity);
    growth.defaults = Arrays.copyOf(columnDefaultValues, capacity);
    growth.defaultHighs = Arrays.copyOf(columnDefaultHighs, capacity);
    growth.defaultKinds = Arrays.copyOf(columnDefaultKinds, capacity);
    growth.types = Arrays.copyOf(columnTypeDescriptors, capacity);
    growth.checkValues = Arrays.copyOf(columnCheckValues, capacity);
    growth.checkHighs = Arrays.copyOf(columnCheckHighs, capacity);
    growth.checkTypes = Arrays.copyOf(columnCheckTypeDescriptors, capacity);
    growth.checks = Arrays.copyOf(columnCheckComparisons, capacity);
    growth.notNull = Arrays.copyOf(columnNotNull, capacity);
    growth.hasDefault = Arrays.copyOf(columnDefaults, capacity);
    growth.unique = Arrays.copyOf(columnUnique, capacity);
    growth.references = Arrays.copyOf(columnReferences, capacity);
  }

  void publishGrowth(SqlCommandColumnGrowth growth) {
    columnReferenceTableNames = growth.referenceTables;
    columnReferenceColumnNames = growth.referenceColumns;
    columnDefaultValues = growth.defaults;
    columnDefaultHighs = growth.defaultHighs;
    columnDefaultKinds = growth.defaultKinds;
    columnTypeDescriptors = growth.types;
    columnCheckValues = growth.checkValues;
    columnCheckHighs = growth.checkHighs;
    columnCheckTypeDescriptors = growth.checkTypes;
    columnCheckComparisons = growth.checks;
    columnNotNull = growth.notNull;
    columnDefaults = growth.hasDefault;
    columnUnique = growth.unique;
    columnReferences = growth.references;
  }

  private SqlIdentifier[] identifiers(SqlIdentifier[] source, int capacity) {
    SqlIdentifier[] grown = Arrays.copyOf(source, capacity);
    for (int index = source.length; index < capacity; index++) grown[index] = new SqlIdentifier();
    return grown;
  }

  void reset(int usedColumns) {
    for (int index = 0; index < usedColumns; index++) {
      columnDefaultHighs[index] = 0;
      columnDefaultValues[index] = 0;
      columnCheckHighs[index] = 0;
      columnCheckValues[index] = 0;
      columnCheckTypeDescriptors[index] = 0;
      columnCheckComparisons[index] = null;
      columnTypeDescriptors[index] = 0;
      columnDefaultKinds[index] = 0;
      columnNotNull[index] = false;
      columnDefaults[index] = false;
      columnUnique[index] = false;
      columnReferences[index] = false;
      columnReferenceTableNames[index].reset();
      columnReferenceColumnNames[index].reset();
    }
    primaryKeyIdentity = false;
    primaryKeyIdentityColumn = -1;
  }

  void initializeColumn(int index) { columnTypeDescriptors[index] = SqlTypeDescriptor.BIGINT; }

  void markNotNull(int columnCount, int index) {
    if (valid(columnCount, index)) columnNotNull[index] = true;
  }

  boolean hasUniqueColumns(int columnCount) { return any(columnUnique, columnCount); }
  boolean hasReferences(int columnCount) { return any(columnReferences, columnCount); }
  boolean hasPrimaryKeyIdentity() { return primaryKeyIdentity; }
  int primaryKeyIdentityColumn() { return primaryKeyIdentityColumn; }

  private int count(boolean[] constraints, int columns) {
    int count = 0;
    for (int index = 0; index < columns; index++) if (constraints[index]) count++;
    return count;
  }

  private boolean any(boolean[] constraints, int columns) {
    for (int index = 0; index < columns; index++) if (constraints[index]) return true;
    return false;
  }

}
