package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Materializes the deepest two-table source into one canonical block boundary. */
final class SqlBlockJoinStage {
  private final BoundSqlStatement bound;
  private final SqlRowProjectionEvaluator projections;
  private final SqlBlockOutputOrder outputOrder;
  private final SqlBlockLegacyJoinRows legacyRows;
  private final SqlBlockUniversalJoinRows universalRows;
  private SqlBlockJoinRows rows;
  private long acceptedRows;

  SqlBlockJoinStage(
      BoundSqlStatement statement,
      SqlBlockSource blockSource,
      SqlSubqueryGraphExecution graph,
      SqlRowProjectionEvaluator projectionEvaluator,
      io.riverdb.engine.relational.RelationalSession relationalSession,
      SqlExpressionEvaluator expressionEvaluator,
      SqlTemporalContext temporalContext,
      SqlSessionShapeBudget shapeBudget,
      SqlBlockOutputOrder blockOutputOrder) {
    bound = statement;
    projections = projectionEvaluator;
    outputOrder = blockOutputOrder;
    legacyRows = new SqlBlockLegacyJoinRows(blockSource, graph);
    universalRows = new SqlBlockUniversalJoinRows(
        relationalSession, expressionEvaluator, temporalContext,
        graph, projectionEvaluator, blockSource, shapeBudget);
  }

  StatusCode prepare(int block, int orderedInnerColumn) {
    boolean nested = bound.executableQuery.edgeCount() > 0;
    SqlCommand command = bound.blockPlans().command(block);
    SqlBoundBooleanPredicateProgram where = nested
        ? bound.nestedBoolean(block) : bound.whereBoolean;
    rows = universalRows;
    StatusCode status = universalRows.prepare(
        block, nested, command, bound.existingJoinContext(block), where,
        orderedInnerColumn);
    if (status == StatusCode.CONFLICT) {
      rows = legacyRows;
      status = legacyRows.prepare(
          bound.existingJoinContext(block), command, block, nested, where);
    }
    if (status.isOk()) status = projections.prepare(bound);
    if (!status.isOk()) {
      StatusCode cleanup = close();
      if (!cleanup.isOk()) status = cleanup;
    }
    return status;
  }

  StatusCode materialize(
      int block, SqlBlockRowStore output, SqlBlockRow sourceRow) {
    SqlCommand command = bound.blockPlans().command(block);
    long resultLimit = command.rowLimit();
    long outputLimit = outputLimit(command, resultLimit);
    long inputLimit = inputLimit(command, resultLimit);
    SqlBlockSchema schema = operandSchema(block, command);
    StatusCode status = outputOrder.beginOperands(command, schema, output);
    boolean began = false;
    if (status.isOk() && inputLimit > 0) {
      status = rows.begin();
      began = status.isOk();
    } else if (status.isOk()) {
      status = rows.skip();
    }
    if (status.isOk()) status = appendRows(output, sourceRow, inputLimit);
    if (began) status = rows.finish(status);
    sourceRow.reset(0);
    if (status.isOk()) status = output.finish();
    return status.isOk() ? output.limit(outputLimit) : status;
  }

  private static long outputLimit(SqlCommand command, long resultLimit) {
    return command.aggregates().invocationCount() > 0 ? Long.MAX_VALUE : resultLimit;
  }

  private static long inputLimit(SqlCommand command, long resultLimit) {
    if (resultLimit == 0) return 0;
    return command.aggregates().invocationCount() > 0 || command.orderBy().count() > 0
        ? Long.MAX_VALUE : resultLimit;
  }

  private SqlBlockSchema operandSchema(int block, SqlCommand command) {
    return command.aggregates().invocationCount() > 0
        ? bound.blockPlans().operandSchema(block) : bound.blockPlans().schema(block);
  }

  private StatusCode appendRows(
      SqlBlockRowStore output, SqlBlockRow sourceRow, long inputLimit) {
    long accepted = 0;
    while (accepted < inputLimit) {
      StatusCode status = rows.next(sourceRow);
      if (status == StatusCode.CONFLICT) return StatusCode.OK;
      if (!status.isOk()) return status;
      status = output.append(sourceRow);
      if (!status.isOk()) return status;
      accepted++;
    }
    return StatusCode.OK;
  }

  StatusCode accumulateScalar(
      SqlAggregateAccumulatorSet accumulator,
      SqlBoundAggregateSet aggregates,
      SqlBlockRow row) {
    acceptedRows = 0;
    StatusCode status = accumulator.reset(aggregates);
    boolean began = false;
    if (status.isOk()) {
      status = rows.begin();
      began = status.isOk();
    }
    while (status.isOk()) {
      status = rows.next(row);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) status = accumulator.accumulateBlock(aggregates, row);
      if (status.isOk()) acceptedRows++;
    }
    if (began) status = rows.finish(status);
    row.reset(0);
    return status;
  }

  long acceptedRows() { return acceptedRows; }

  StatusCode close() {
    StatusCode status = universalRows.close();
    StatusCode legacy = legacyRows.close();
    if (status.isOk()) status = legacy;
    rows = null;
    return status;
  }

  boolean hasResources() { return rows != null && rows.hasResources(); }

}
