package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.schema.KeyDescriptor;

/** Compact physical root-row schema and key order for one dependent lookup. */
final class SqlKeyOrderedLookupRootShape {
  private final int[] sortColumns = new int[KeyDescriptor.MAXIMUM_PARTS];
  private final boolean[] descending = new boolean[KeyDescriptor.MAXIMUM_PARTS];
  private final SqlKeyOrderedLookupRootColumns columns =
      new SqlKeyOrderedLookupRootColumns();
  private final SqlBlockSchema schema;
  private TableDefinition root;
  private int keyCount;
  private int storedColumnCount;
  private int publicKeyColumn = -1;

  SqlKeyOrderedLookupRootShape(SqlSessionShapeBudget budget) {
    schema = new SqlBlockSchema(budget);
  }

  StatusCode prepare(
      TableDefinition table,
      SqlBoundBooleanPredicateProgram on,
      SqlBoundBooleanPredicateProgram where,
      int[] keySourceColumns,
      int keys) {
    reset();
    if (table == null || keySourceColumns == null || keys <= 0
        || keys > sortColumns.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    root = table;
    keyCount = keys;
    StatusCode status = columns.prepare(root, on, where, keySourceColumns, keyCount);
    return status.isOk() ? build(keySourceColumns) : status;
  }

  private StatusCode build(int[] keySourceColumns) {
    storedColumnCount = columns.count();
    publicKeyColumn = storedColumnCount++;
    schema.set(storedColumnCount);
    for (int lane = 0; lane < publicKeyColumn; lane++) {
      int column = columns.sourceColumn(lane);
      schema.setColumn(
          lane, root.columnName(column),
          root.typeDescriptor(column), root.isNullable(column));
    }
    schema.setColumn(publicKeyColumn, "", SqlTypeDescriptor.BIGINT, false);
    StatusCode status = schema.status();
    for (int key = 0; status.isOk() && key < keyCount; key++) {
      sortColumns[key] = columns.storedColumn(keySourceColumns[key]);
      descending[key] = false;
      if (sortColumns[key] < 0) status = StatusCode.INVARIANT_BROKEN;
    }
    return status;
  }

  void reset() {
    columns.reset();
    schema.reset();
    root = null;
    keyCount = 0;
    storedColumnCount = 0;
    publicKeyColumn = -1;
  }

  SqlBlockSchema schema() { return schema; }
  int[] sortColumns() { return sortColumns; }
  boolean[] descending() { return descending; }
  int keyCount() { return keyCount; }
  int sourceColumn(int lane) { return columns.sourceColumn(lane); }
  int publicKeyColumn() { return publicKeyColumn; }
  int storedColumnCount() { return storedColumnCount; }
  int rootColumnCount() { return root == null ? 0 : root.columnCount(); }
  int rootTypeDescriptor(int column) { return root.typeDescriptor(column); }
}
