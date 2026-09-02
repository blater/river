package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Dispatches scan families that own their entire row-publication path. */
final class SqlQuerySpecialScan {
  private SqlQuerySpecialScan() { }

  static void next(
      SqlUnionSessionExecution unions,
      SqlUniversalJoinExecution universalJoins,
      SqlDescriptorScanExecution descriptorScans,
      SqlPhysicalPlan plan,
      SqlCatalogScanExecution catalogs,
      SqlActiveScanState activeScan,
      long[] projectedValues,
      int[] explainDescriptors,
      int[] boundDescriptors,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      Result result) {
    result.matched = true;
    if (unions.active()) result.status = nextUnion(unions, cursor, row);
    else if (universalJoins.active()) result.status = universalJoins.next(cursor, row);
    else if (descriptorScans.active()) result.status = descriptorScans.next(cursor, row);
    else if (plan.catalogObjectScan()) result.status = catalogs.nextObject(cursor, row);
    else if (plan.catalogIndexScan()) result.status = catalogs.nextIndex(cursor, row);
    else if (plan.catalogColumnScan()) result.status = catalogs.nextColumn(cursor, row);
    else if (plan.explainResult()) result.status = SqlExplainScanPublisher.next(
        plan, activeScan, projectedValues,
        plan.copyResultDescriptors(explainDescriptors, cursor.projectedColumnCount()),
        cursor, row);
    else if (plan.aggregate()) result.status = SqlAggregateScanPublisher.next(
        plan, activeScan, projectedValues,
        plan.copyResultDescriptors(boundDescriptors, cursor.projectedColumnCount()),
        cursor, row);
    else {
      result.matched = false;
      result.status = StatusCode.OK;
    }
  }

  private static StatusCode nextUnion(
      SqlUnionSessionExecution unions, SqlScanCursor cursor, SqlScanRowResult row) {
    StatusCode status = unions.next(row);
    if (status.isOk()) cursor.rowReturned();
    return status;
  }

  static final class Result {
    private StatusCode status = StatusCode.OK;
    private boolean matched;

    StatusCode status() { return status; }
    boolean matched() { return matched; }
  }
}
