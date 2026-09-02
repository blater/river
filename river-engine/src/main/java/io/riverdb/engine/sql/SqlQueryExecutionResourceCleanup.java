package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;

/** Closes the owned physical resources of one query execution. */
final class SqlQueryExecutionResourceCleanup {
  private SqlQueryExecutionResourceCleanup() { }

  static StatusCode close(
      RelationalSession session,
      SqlUniversalJoinExecution universalJoins,
      SqlDescriptorScanExecution descriptorScans,
      SqlUnionSessionExecution unions,
      SqlCatalogScanExecution catalogs,
      SqlSubqueryGraphExecution subqueries,
      SqlJoinExecution joins,
      SqlPointQueryExecution pointQueries,
      SqlActiveScanState activeScan,
      SqlSortExecution sorts,
      SqlBlockPipelineExecution blockPipeline,
      SqlGroupedExecution groups) {
    StatusCode status = universalJoins.active() || universalJoins.matched()
        ? universalJoins.close() : StatusCode.OK;
    StatusCode descriptorStatus = descriptorScans.active() || descriptorScans.matched()
        ? descriptorScans.close() : StatusCode.OK;
    StatusCode unionStatus = unions.active() ? unions.close() : StatusCode.OK;
    status = firstFailure(status, descriptorStatus);
    status = firstFailure(status, unionStatus);
    status = firstFailure(status, catalogs.close());
    if (subqueries.hasResources()) {
      status = firstFailure(status, subqueries.reset());
    }
    if (joins.hasResources()) {
      status = firstFailure(status, joins.close());
    }
    if (pointQueries.hasResources()) {
      status = firstFailure(status, pointQueries.closeResources());
    }
    if (activeScan.relational().isActive()) {
      status = firstFailure(status, session.closeScan(activeScan.relational()));
    }
    if (sorts.hasResources()) {
      status = firstFailure(status, sorts.close());
    }
    if (blockPipeline != null && blockPipeline.hasResources()) {
      status = firstFailure(status, blockPipeline.close());
    }
    status = firstFailure(status, groups.closeResources());
    return status;
  }

  private static StatusCode firstFailure(StatusCode current, StatusCode next) {
    return current.isOk() ? next : current;
  }
}
