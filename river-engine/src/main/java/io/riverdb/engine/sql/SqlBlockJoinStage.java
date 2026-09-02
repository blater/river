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

  StatusCode prepare(int block) {
    boolean nested = bound.executableQuery.edgeCount() > 0;
    SqlCommand command = bound.blockPlans().command(block);
    SqlBoundBooleanPredicateProgram where = nested
        ? bound.nestedBoolean(block) : bound.whereBoolean;
    rows = universalRows;
    StatusCode status = universalRows.prepare(
        block, nested, command, bound.existingJoinContext(block), where);
    if (status == StatusCode.CONFLICT) {
      rows = legacyRows;
      status = legacyRows.prepare(
          bound.existingJoinContext(block), command, block, nested, where);
    }
    if (status.isOk()) status = projections.prepare(bound);
    if (!status.isOk()) close();
    return status;
  }

  StatusCode materialize(
      int block, SqlBlockRowStore output, SqlBlockRow sourceRow) {
    SqlCommand command = bound.blockPlans().command(block);
    long resultLimit = command.rowLimit();
    boolean aggregates = command.aggregateInvocationCount() > 0;
    long outputLimit = aggregates ? Long.MAX_VALUE : resultLimit;
    long inputLimit = resultLimit == 0 ? 0
        : aggregates || command.isOrdered() ? Long.MAX_VALUE : resultLimit;
    SqlBlockSchema schema = bound.blockPlans().operandSchema(block);
    if (command.aggregateInvocationCount() == 0) {
      schema = bound.blockPlans().schema(block);
    }
    StatusCode status = outputOrder.beginOperands(command, schema, output);
    boolean began = false;
    if (status.isOk() && inputLimit > 0) {
      status = rows.begin();
      began = status.isOk();
    } else if (status.isOk()) {
      status = rows.skip();
    }
    long accepted = 0;
    while (status.isOk() && accepted < inputLimit) {
      status = rows.next(sourceRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) status = output.append(sourceRow);
      if (status.isOk()) accepted++;
    }
    if (began) status = rows.finish(status);
    sourceRow.reset(0);
    if (status.isOk()) status = output.finish();
    return status.isOk() ? output.limit(outputLimit) : status;
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

}
