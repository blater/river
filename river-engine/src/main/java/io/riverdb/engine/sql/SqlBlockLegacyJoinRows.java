package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Retained adapter for the established two-table block JOIN source. */
final class SqlBlockLegacyJoinRows implements SqlBlockJoinRows {
  private final SqlBlockSource source;
  private final SqlSubqueryGraphExecution subqueries;

  SqlBlockLegacyJoinRows(
      SqlBlockSource blockSource, SqlSubqueryGraphExecution graph) {
    source = blockSource;
    subqueries = graph;
  }

  StatusCode prepare(
      SqlBoundJoinContext context,
      SqlCommand command,
      int block,
      boolean nested,
      SqlBoundBooleanPredicateProgram where) {
    return source.configureJoin(
        context,
        command,
        where,
        nested ? subqueries.joinPredicates(block) : null);
  }

  @Override
  public StatusCode begin() { return source.beginJoin(); }

  @Override
  public StatusCode next(SqlBlockRow row) { return source.nextJoin(row); }

  @Override
  public StatusCode finish(StatusCode body) {
    return subqueries.hasResources() ? body : source.finishJoin(body);
  }

  @Override
  public StatusCode skip() {
    source.resetJoinMetrics();
    return StatusCode.OK;
  }

  @Override
  public StatusCode close() { return StatusCode.OK; }

  @Override
  public boolean hasResources() { return source.hasResources(); }
}
