package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.CatalogIndexResult;
import io.riverdb.engine.relational.CatalogObjectResult;
import io.riverdb.engine.relational.RelationalSession;

/** Opens and advances SHOW TABLES and SHOW INDEXES catalog operators. */
final class SqlCatalogScanExecution {
  private static final String TABLE_TYPE = "TABLE";
  private static final String VIEW_TYPE = "VIEW";

  private final RelationalSession session;
  private final SqlPhysicalPlan plan;
  private final SqlActiveScanState scan;
  private final CatalogObjectResult object = new CatalogObjectResult();
  private final CatalogIndexResult index = new CatalogIndexResult();
  private final long[] values = new long[5];
  private final int[] types = new int[5];

  SqlCatalogScanExecution(
      RelationalSession relationalSession,
      SqlPhysicalPlan physicalPlan,
      SqlActiveScanState activeScan) {
    session = relationalSession;
    plan = physicalPlan;
    scan = activeScan;
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
      plan.setResultColumn(1, 1, SqlTypeDescriptor.varchar(64), "column_name");
      plan.setResultColumn(2, 2, SqlTypeDescriptor.BOOLEAN, "is_unique");
      plan.setResultColumn(3, 3, SqlTypeDescriptor.BOOLEAN, "is_primary");
      plan.setResultColumn(4, 4, SqlTypeDescriptor.BOOLEAN, "is_constraint");
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
