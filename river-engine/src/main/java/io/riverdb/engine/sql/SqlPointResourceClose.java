package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Closes retained point-query resources in ownership order. */
final class SqlPointResourceClose {
  private SqlPointResourceClose() {}

  static StatusCode close(
      SqlSubqueryGraphExecution subqueries,
    SqlPointQueryExecution points,
      boolean blockOwned,
      SqlBlockPipelineExecution blocks) {
    StatusCode status = subqueries.reset();
    StatusCode pointStatus = points.closeResources();
    if (status.isOk()) status = pointStatus;
    if (blockOwned && blocks != null && blocks.hasResources()) {
      StatusCode blockStatus = blocks.close();
      if (status.isOk()) status = blockStatus;
    }
    return status;
  }
}
