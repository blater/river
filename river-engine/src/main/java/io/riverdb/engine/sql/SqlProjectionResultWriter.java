package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Publishes one projected row into its final reusable result owner. */
final class SqlProjectionResultWriter {
  StatusCode writePoint(
      SqlExecutionResult result,
      long primaryKey,
      HeapRowResult source,
      BoundSqlStatement bound,
      SqlProjectedRow projected) {
    return SqlProjectionPublication.writePoint(
        result, primaryKey, source, bound, projected);
  }

  StatusCode writeScan(
      SqlScanRowResult result,
      long primaryKey,
      HeapRowResult source,
      TableDefinition table,
      SqlScanCursor cursor,
      int[] descriptors,
      SqlProjectedRow projected) {
    return SqlProjectionPublication.writeScan(
        result, primaryKey, source, table, cursor, descriptors, projected);
  }
}
