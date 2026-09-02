package io.riverdb.engine.sql;

/** Consolidates point-query retained-resource ownership checks. */
final class SqlPointResourceState {
  private SqlPointResourceState() {}

  static boolean has(
      SqlSubqueryGraphExecution subqueries,
      SqlPointQueryExecution points,
      boolean blockOwned,
      SqlBlockPipelineExecution blocks) {
    return subqueries.hasResources() || points.hasResources()
        || blockOwned && blocks != null && blocks.hasResources();
  }
}
