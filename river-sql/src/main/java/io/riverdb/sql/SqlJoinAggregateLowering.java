package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Lowers a joined aggregate into a source scan and cardinality-preserving aggregate block. */
final class SqlJoinAggregateLowering {
  private SqlJoinAggregateLowering() { }

  static StatusCode lower(SqlQuery query, SqlCommand parsed) {
    SqlCommand root = query.nextBlock();
    SqlCommand sourceBlock = query.nextBlock();
    if (root == null || sourceBlock == null) return StatusCode.QUERY_TOO_COMPLEX;
    StatusCode status = root.copyBlockFrom(parsed);
    if (status.isOk()) lowerJoinAggregateRoot(root);
    if (status.isOk()) status = sourceBlock.copyBlockFrom(parsed);
    if (status.isOk()) status = lowerJoinAggregateSource(sourceBlock, root);
    if (status.isOk()) query.markBlockPipeline();
    return status.isOk() ? query.compileBlockPipeline(parsed) : status;
  }


  private static StatusCode lowerJoinAggregateSource(SqlCommand command, SqlCommand root) {
    command.aggregates.materializeOperandlessOutputs(command.projections, command.columnCount);
    for (int group = 0; group < command.grouping.count(); group++) {
      int projection = command.grouping.projection(group);
      if (projection < 0) {
        projection = command.columnCount;
        StatusCode status = SqlCommandProjectionView.appendGroupExpression(
            command, command.grouping.expression(group));
        if (!status.isOk()) return status;
      }
      root.grouping.setOperandProjection(group, projection);
    }
    command.aggregates.reset();
    command.grouping.reset();
    command.booleanHavingPredicates.reset();
    command.orderBy.reset();
    command.descendingOrder = false;
    command.rowLimit = Long.MAX_VALUE;
    command.type = SqlCommandType.JOIN_SCAN;
    return StatusCode.OK;
  }

  private static void lowerJoinAggregateRoot(SqlCommand command) {
    command.wherePredicates.reset();
    if (command.joinChain != null) command.joinChain.clearPredicates();
    command.type = SqlAggregateCommandType.route(
        command.aggregates.kind(0), command.grouping.count() > 0);
  }
}
