package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;

/** Closes the owned physical resources of one query execution. */
final class SqlQueryExecutionResourceCleanup {
  private SqlQueryExecutionResourceCleanup() { }

  static StatusCode close(
      RelationalSession session,
      SqlCatalogScanExecution catalogs,
      SqlSubqueryGraphExecution subqueries,
      SqlJoinExecution joins,
      SqlPointQueryExecution pointQueries,
      SqlActiveScanState activeScan,
      SqlSortExecution sorts,
      SqlBlockPipelineExecution blockPipeline,
      SqlGroupedExecution groups) {
    StatusCode status = catalogs.close();
    if (status.isOk()) {
      status = subqueries.reset();
    }
    status = joins.closeAfter(status);
    if (status.isOk() && pointQueries.hasResources()) {
      status = pointQueries.closeResources();
    }
    if (status.isOk() && activeScan.relational().isActive()) {
      status = session.closeScan(activeScan.relational());
    }
    if (status.isOk() && sorts.hasResources()) {
      status = sorts.close();
    }
    if (status.isOk() && blockPipeline != null && blockPipeline.hasResources()) {
      status = blockPipeline.close();
    }
    groups.resetText();
    return status;
  }
}
