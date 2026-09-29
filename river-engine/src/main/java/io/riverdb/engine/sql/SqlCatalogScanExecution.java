package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.CatalogIndexResult;
import io.riverdb.engine.relational.CatalogObjectResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.schema.TableDescriptor;

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
  private final SqlCatalogDescriptorResolution descriptors;
  private final SqlCatalogRowPublisher row = new SqlCatalogRowPublisher();
  private final SqlTypeNameFormatter typeName = new SqlTypeNameFormatter();
  private final SqlDescriptorCatalogIndexes descriptorIndexes =
      new SqlDescriptorCatalogIndexes();
  private int columnIndex;
  private TableDescriptor descriptorTable;

  SqlCatalogScanExecution(
      RelationalSession relationalSession,
      SqlPhysicalPlan physicalPlan,
      SqlActiveScanState activeScan,
      TableDefinition table) {
    session = relationalSession;
    plan = physicalPlan;
    scan = activeScan;
    columns = table;
    descriptors = new SqlCatalogDescriptorResolution(relationalSession);
  }

  StatusCode beginObjects() {
    StatusCode status = plan.beginResult(2);
    if (!status.isOk()) return status;
    status = session.beginCatalogObjectScan(scan.catalogObjects());
    if (!status.isOk()) return status;
    plan.setResultColumn(0, 0, SqlTypeDescriptor.varchar(64), "table_name");
    plan.setResultColumn(1, 1, SqlTypeDescriptor.varchar(64), "table_type");
    return scan.claim();
  }

  StatusCode beginIndexes(CharSequence tableName) {
    descriptorTable = null;
    descriptors.reset();
    StatusCode status = plan.beginResult(5);
    if (!status.isOk()) return status;
    status = session.beginCatalogIndexScan(tableName, scan.catalogIndexes());
    if (!status.isOk()) status = descriptors.resolve(tableName, status);
    if (status.isOk() && descriptors.table() != null) {
      descriptorTable = descriptors.table();
      descriptorIndexes.begin(descriptorTable);
    }
    if (!status.isOk()) return status;
    plan.setResultColumn(0, 0, SqlTypeDescriptor.varchar(64), "index_name");
    plan.setResultNullable(0, true);
    plan.setResultColumn(1, 1, SqlTypeDescriptor.varchar(64), "column_name");
    plan.setResultColumn(2, 2, SqlTypeDescriptor.BOOLEAN, "is_unique");
    plan.setResultColumn(3, 3, SqlTypeDescriptor.BOOLEAN, "is_primary");
    plan.setResultColumn(4, 4, SqlTypeDescriptor.BOOLEAN, "is_constraint");
    return scan.claim();
  }

  StatusCode beginColumns(CharSequence tableName) {
    columns.reset();
    descriptorTable = null;
    descriptors.reset();
    columnIndex = 0;
    StatusCode status = plan.beginResult(4);
    if (!status.isOk()) return status;
    status = session.resolveTable(tableName, columns);
    if (!status.isOk()) status = descriptors.resolve(tableName, status);
    if (status.isOk()) descriptorTable = descriptors.table();
    if (!status.isOk()) return status;
    plan.setResultColumn(0, 0, SqlTypeDescriptor.varchar(64), "column_name");
    plan.setResultColumn(1, 1, SqlTypeDescriptor.varchar(48), "type");
    plan.setResultColumn(2, 2, SqlTypeDescriptor.BOOLEAN, "is_nullable");
    plan.setResultColumn(3, 3, SqlTypeDescriptor.BIGINT, "ordinal");
    return scan.claim();
  }

  StatusCode nextObject(SqlScanCursor cursor, SqlScanRowResult result) {
    StatusCode status = session.nextCatalogObject(scan.catalogObjects(), object);
    if (!status.isOk()) return status;
    if (!object.isAvailable()) return StatusCode.CONFLICT;
    status = row.object(
        plan,
        object,
        object.type() == CatalogObjectResult.TABLE ? TABLE_TYPE : VIEW_TYPE,
        result);
    return publish(cursor, status);
  }

  StatusCode nextIndex(SqlScanCursor cursor, SqlScanRowResult result) {
    if (descriptorTable != null) return nextDescriptorIndex(cursor, result);
    StatusCode status = session.nextCatalogIndex(scan.catalogIndexes(), index);
    if (!status.isOk()) return status;
    if (!index.isAvailable()) return StatusCode.CONFLICT;
    status = row.index(plan, index, result);
    return publish(cursor, status);
  }

  StatusCode nextColumn(SqlScanCursor cursor, SqlScanRowResult result) {
    int count = descriptorTable == null
        ? columns.columnCount() : descriptorTable.columnCount();
    if (columnIndex >= count) return StatusCode.CONFLICT;
    int column = columnIndex++;
    int descriptor = descriptorTable == null
        ? columns.typeDescriptor(column) : descriptorTable.typeDescriptorAt(column);
    int length = typeName.format(descriptor);
    StatusCode status = length < 0
        ? StatusCode.CORRUPTION
        : descriptorTable == null
            ? row.column(plan, columns, column, typeName.text(), length, result)
            : row.column(plan, descriptorTable, column, typeName.text(), length, result);
    return publish(cursor, status);
  }

  private StatusCode nextDescriptorIndex(
      SqlScanCursor cursor, SqlScanRowResult result) {
    StatusCode status = descriptorIndexes.next();
    if (status.isOk()) status = row.index(
        plan, descriptorTable, descriptorIndexes.key(), descriptorIndexes.publishedPart(),
        descriptorIndexes.primary(), descriptorIndexes.publishedPart(), result);
    return publish(cursor, status);
  }

  private StatusCode publish(SqlScanCursor cursor, StatusCode status) {
    if (status.isOk()) cursor.rowReturned();
    return status;
  }

  StatusCode close() {
    StatusCode status = StatusCode.OK;
    if (scan.catalogObjects().isActive()) {
      status = session.closeCatalogObjectScan(scan.catalogObjects());
    }
    if (scan.catalogIndexes().isActive()) {
      StatusCode indexStatus = session.closeCatalogIndexScan(scan.catalogIndexes());
      if (status.isOk()) status = indexStatus;
    }
    StatusCode descriptorStatus = descriptors.close();
    if (status.isOk()) status = descriptorStatus;
    if (status.isOk()) descriptorTable = null;
    return status;
  }
}
