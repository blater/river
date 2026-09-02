package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;

/** Owns one materialized UNION result through the public scan lifecycle. */
final class SqlUnionSessionExecution {
  private final SqlUnionPipelineLeafSource leaves;
  private final SqlUnionExecution union;
  private final SqlBlockOutputShape output = new SqlBlockOutputShape();
  private final SqlBlockRow row = new SqlBlockRow();
  private boolean active;

  SqlUnionSessionExecution(
      RelationalSession session,
      SqlBinder binder,
      SqlExpressionEvaluator expressions,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget budget) {
    leaves = new SqlUnionPipelineLeafSource(
        session, binder, expressions, temporal, budget);
    union = new SqlUnionExecution(budget);
  }

  StatusCode prepare(SqlQuery query, SqlCommand command) {
    StatusCode status = close();
    if (status.isOk()) status = leaves.prepare(query);
    boolean explainOnly = query != null && query.isExplain() && !query.isAnalyze();
    if (status.isOk()) {
      status = explainOnly
          ? union.describe(query, command, leaves)
          : union.run(query, command, leaves);
    }
    if (status.isOk() && explainOnly) status = leaves.close(StatusCode.OK);
    if (status.isOk()) status = output.prepare(union.schema());
    if (!status.isOk()) return leaves.close(status);
    active = true;
    return StatusCode.OK;
  }

  StatusCode next(SqlScanRowResult result) {
    return !active ? StatusCode.CONFLICT : SqlBlockOutputPublisher.next(
        union.output(), row, union.schema(), output, result);
  }

  SqlBlockSchema schema() { return union.schema(); }
  SqlUnionStagePlan stagePlan() { return union.stagePlan(); }
  long rowCount() { return active ? union.output().rowCount() : 0; }
  boolean active() { return active; }

  StatusCode close() {
    StatusCode unionStatus = union.close();
    StatusCode leafStatus = leaves.close(StatusCode.OK);
    if (unionStatus.isOk() && leafStatus.isOk()) active = false;
    return unionStatus.isOk() ? leafStatus : unionStatus;
  }
}
