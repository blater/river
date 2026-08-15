package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.CatalogIndexResult;
import io.riverdb.engine.relational.CatalogObjectResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;

/** Opens and advances bounded SHOW catalog operators. */
final class SqlCatalogScanExecution {
  private static final String TABLE_TYPE = "TABLE";
  private static final String VIEW_TYPE = "VIEW";

  private final RelationalSession session;
  private final SqlPhysicalPlan plan;
  private final SqlActiveScanState scan;
  private final CatalogObjectResult object = new CatalogObjectResult();
  private final CatalogIndexResult index = new CatalogIndexResult();
  private final TableDefinition columns;
  private final long[] values = new long[5];
  private final int[] types = new int[5];
  private final char[] typeName = new char[48];
  private int columnIndex;

  SqlCatalogScanExecution(
      RelationalSession relationalSession,
      SqlPhysicalPlan physicalPlan,
      SqlActiveScanState activeScan,
      TableDefinition table) {
    session = relationalSession;
    plan = physicalPlan;
    scan = activeScan;
    columns = table;
  }

  StatusCode beginObjects() {
    StatusCode status = session.beginCatalogObjectScan(scan.catalogObjects());
    if (status.isOk()) {
      plan.setResultColumn(0, 0, SqlTypeDescriptor.varchar(64), "table_name");
      plan.setResultColumn(1, 1, SqlTypeDescriptor.varchar(64), "table_type");
      status = scan.claim();
    }
    return status;
  }

  StatusCode beginIndexes(CharSequence tableName) {
    StatusCode status = session.beginCatalogIndexScan(tableName, scan.catalogIndexes());
    if (status.isOk()) {
      plan.setResultColumn(0, 0, SqlTypeDescriptor.varchar(64), "index_name");
      plan.setResultNullable(0, true);
      plan.setResultColumn(1, 1, SqlTypeDescriptor.varchar(64), "column_name");
      plan.setResultColumn(2, 2, SqlTypeDescriptor.BOOLEAN, "is_unique");
      plan.setResultColumn(3, 3, SqlTypeDescriptor.BOOLEAN, "is_primary");
      plan.setResultColumn(4, 4, SqlTypeDescriptor.BOOLEAN, "is_constraint");
      status = scan.claim();
    }
    return status;
  }

  StatusCode beginColumns(CharSequence tableName) {
    columns.reset();
    columnIndex = 0;
    StatusCode status = session.resolveTable(tableName, columns);
    if (status.isOk()) {
      plan.setResultColumn(0, 0, SqlTypeDescriptor.varchar(64), "column_name");
      plan.setResultColumn(1, 1, SqlTypeDescriptor.varchar(48), "type");
      plan.setResultColumn(2, 2, SqlTypeDescriptor.BOOLEAN, "is_nullable");
      plan.setResultColumn(3, 3, SqlTypeDescriptor.BIGINT, "ordinal");
      status = scan.claim();
    }
    return status;
  }

  StatusCode nextObject(SqlScanCursor cursor, SqlScanRowResult result) {
    StatusCode status = session.nextCatalogObject(scan.catalogObjects(), object);
    if (!status.isOk()) return status;
    if (!object.isAvailable()) return StatusCode.CONFLICT;
    values[0] = 0;
    values[1] = 0;
    types[0] = plan.resultType(0);
    types[1] = plan.resultType(1);
    result.set(0, values, 0, types, 2);
    status = result.setTextAt(0, object.name());
    if (status.isOk()) {
      status = result.setTextAt(
          1, object.type() == CatalogObjectResult.TABLE ? TABLE_TYPE : VIEW_TYPE);
    }
    if (status.isOk()) cursor.rowReturned();
    return status;
  }

  StatusCode nextIndex(SqlScanCursor cursor, SqlScanRowResult result) {
    StatusCode status = session.nextCatalogIndex(scan.catalogIndexes(), index);
    if (!status.isOk()) return status;
    if (!index.isAvailable()) return StatusCode.CONFLICT;
    values[0] = 0;
    values[1] = 0;
    values[2] = index.isUnique() ? 1 : 0;
    values[3] = index.isPrimary() ? 1 : 0;
    values[4] = index.isConstraint() ? 1 : 0;
    for (int position = 0; position < 5; position++) {
      types[position] = plan.resultType(position);
    }
    result.set(0, values, index.isPrimary() ? 1 : 0, types, 5);
    if (!index.isPrimary()) {
      status = result.setTextAt(0, index.indexName());
    }
    if (status.isOk()) status = result.setTextAt(1, index.columnName());
    if (status.isOk()) cursor.rowReturned();
    return status;
  }

  StatusCode nextColumn(SqlScanCursor cursor, SqlScanRowResult result) {
    if (columnIndex >= columns.columnCount()) return StatusCode.CONFLICT;
    int column = columnIndex++;
    values[0] = 0;
    values[1] = 0;
    values[2] = columns.isNullable(column) ? 1 : 0;
    values[3] = column + 1;
    for (int index = 0; index < 4; index++) types[index] = plan.resultType(index);
    result.set(column, values, 0, types, 4);
    StatusCode status = result.setTextAt(0, columns.columnName(column));
    int length = typeName(columns.typeDescriptor(column));
    if (status.isOk()) {
      status = length < 0
          ? StatusCode.CORRUPTION : result.setTextAt(1, typeName, length);
    }
    if (status.isOk()) cursor.rowReturned();
    return status;
  }

  private int typeName(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return switch (type) {
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> copy("BIGINT");
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> copy("BOOLEAN");
      case SqlTypeDescriptor.TYPE_ID_DATE -> copy("DATE");
      case SqlTypeDescriptor.TYPE_ID_DECIMAL ->
          parameterized("DECIMAL", descriptor, true);
      case SqlTypeDescriptor.TYPE_ID_VARCHAR ->
          parameterized("VARCHAR", descriptor, false);
      case SqlTypeDescriptor.TYPE_ID_TIME ->
          parameterized("TIME", descriptor, false);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          parameterized("TIMESTAMP", descriptor, false);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          timestampWithZone(descriptor);
      default -> -1;
    };
  }

  private int parameterized(String name, int descriptor, boolean scale) {
    int length = copy(name);
    typeName[length++] = '(';
    length = number(SqlTypeDescriptor.parameterOne(descriptor), length);
    if (scale) {
      typeName[length++] = ',';
      length = number(SqlTypeDescriptor.parameterTwo(descriptor), length);
    }
    typeName[length++] = ')';
    return length;
  }

  private int timestampWithZone(int descriptor) {
    int length = parameterized("TIMESTAMP", descriptor, false);
    return append(" WITH TIME ZONE", length);
  }

  private int copy(String text) {
    return append(text, 0);
  }

  private int append(String text, int offset) {
    for (int index = 0; index < text.length(); index++) {
      typeName[offset++] = text.charAt(index);
    }
    return offset;
  }

  private int number(int value, int offset) {
    if (value >= 100) {
      typeName[offset++] = (char) ('0' + value / 100);
      value %= 100;
      typeName[offset++] = (char) ('0' + value / 10);
    } else if (value >= 10) {
      typeName[offset++] = (char) ('0' + value / 10);
    }
    typeName[offset++] = (char) ('0' + value % 10);
    return offset;
  }

  StatusCode close() {
    StatusCode status = StatusCode.OK;
    if (scan.catalogObjects().isActive()) {
      status = session.closeCatalogObjectScan(scan.catalogObjects());
    }
    if (status.isOk() && scan.catalogIndexes().isActive()) {
      status = session.closeCatalogIndexScan(scan.catalogIndexes());
    }
    return status;
  }
}
