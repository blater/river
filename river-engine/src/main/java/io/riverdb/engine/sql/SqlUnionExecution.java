package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;

/** Session-reusable top-level UNION materialization and root ordering owner. */
final class SqlUnionExecution {
  private final SqlUnionSchema schema;
  private final SqlUnionNodeExecution nodes;
  private final SqlBlockOutputOrder ordering = new SqlBlockOutputOrder();
  private final SqlBlockRowStore output;
  private final SqlUnionStagePlan stagePlan = new SqlUnionStagePlan();

  SqlUnionExecution(SqlSessionShapeBudget budget) {
    schema = new SqlUnionSchema(budget);
    nodes = new SqlUnionNodeExecution(budget);
    output = new SqlBlockRowStore(budget);
  }

  StatusCode run(
      SqlQuery query, SqlCommand outputCommand, SqlUnionLeafSource leaves) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    status = validate(query, outputCommand, leaves);
    if (!status.isOk()) return status;
    status = schema.prepare(query, leaves);
    if (status.isOk()) status = nodes.prepare(leaves, schema.output());
    if (status.isOk()) status = ordering.beginOutput(outputCommand, schema.output(), output);
    if (status.isOk()) status = nodes.append(query, query.setRootNode(), output, 0);
    if (status.isOk()) status = output.finish();
    long setRows = status.isOk() ? output.rowCount() : -1;
    if (status.isOk()) status = output.limit(query.setRowLimit());
    if (status.isOk()) status = stagePlan.prepare(
        query,
        query.isAnalyze() ? setRows : -1,
        query.isAnalyze() ? output.rowCount() : -1);
    if (!status.isOk()) close();
    return status;
  }

  StatusCode describe(
      SqlQuery query, SqlCommand outputCommand, SqlUnionLeafSource leaves) {
    StatusCode status = close();
    if (status.isOk()) status = validate(query, outputCommand, leaves);
    if (status.isOk()) status = schema.prepare(query, leaves);
    if (status.isOk()) status = stagePlan.prepare(query, -1, -1);
    if (!status.isOk()) close();
    return status;
  }

  SqlBlockSchema schema() { return schema.output(); }
  SqlBlockRowStore output() { return output; }
  SqlUnionStagePlan stagePlan() { return stagePlan; }

  StatusCode close() {
    StatusCode status = nodes.close();
    StatusCode outputStatus = output.close();
    return status.isOk() ? outputStatus : status;
  }

  private static StatusCode validate(
      SqlQuery query, SqlCommand outputCommand, SqlUnionLeafSource leaves) {
    return query == null || outputCommand == null || leaves == null
        || !query.hasSetExpression()
        ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.OK;
  }
}
