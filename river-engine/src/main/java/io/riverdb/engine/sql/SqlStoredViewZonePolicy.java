package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;
import io.riverdb.sql.SqlScalarExpression;

/** Validates every explicit temporal-zone operand in one stored view. */
final class SqlStoredViewZonePolicy {
  private SqlStoredViewZonePolicy() {}

  static StatusCode validate(
      SqlCommand command, SqlQuery query, SqlTemporalZoneNames zones) {
    for (int block = 0; block < Math.max(1, query.blockCount()); block++) {
      SqlCommand current = query.blockCount() == 0 ? command : query.block(block);
      StatusCode status = command(current, zones);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  static StatusCode validate(
      SqlQuery query, int firstBlock, SqlTemporalZoneNames zones) {
    if (firstBlock < 0 || firstBlock >= query.blockCount()) {
      return StatusCode.CORRUPTION;
    }
    for (int block = firstBlock; block < query.blockCount(); block++) {
      StatusCode status = command(query.block(block), zones);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private static StatusCode command(
      SqlCommand command, SqlTemporalZoneNames zones) {
    for (int projection = 0; projection < command.columnCount(); projection++) {
      StatusCode status = expression(
          command, command.projectionExpression(projection), zones);
      if (!status.isOk()) return status;
    }
    for (int lane = 0; lane < SqlCommand.MAXIMUM_COLUMNS; lane++) {
      SqlScalarExpression expression = command.aggregateOperandExpression(lane);
      if (expression == null || !expression.isAvailable()) continue;
      StatusCode status = expression(command, expression, zones);
      if (!status.isOk()) return status;
    }
    return having(command, zones);
  }

  private static StatusCode having(
      SqlCommand command, SqlTemporalZoneNames zones) {
    for (int predicate = 0; predicate < command.havingPredicateCount(); predicate++) {
      int zoneNodes = 0;
      for (int node = 0; node < command.havingNodeCount(predicate); node++) {
        if (command.havingOperator(predicate, node) != SqlScalarExpression.AT_TIME_ZONE) {
          continue;
        }
        if (++zoneNodes > 1) return StatusCode.FEATURE_NOT_SUPPORTED;
        if (zones.parse(command, command.havingOperand(predicate, node)) == null) {
          return StatusCode.INVALID_TIME_ZONE_DISPLACEMENT;
        }
      }
    }
    return StatusCode.OK;
  }

  private static StatusCode expression(
      SqlCommand command,
      SqlScalarExpression expression,
      SqlTemporalZoneNames zones) {
    if (expression == null || !expression.isAvailable()) return StatusCode.OK;
    int zoneNodes = 0;
    for (int node = 0; node < expression.nodeCount(); node++) {
      if (expression.operator(node) != SqlScalarExpression.AT_TIME_ZONE) continue;
      if (++zoneNodes > 1) return StatusCode.FEATURE_NOT_SUPPORTED;
      if (zones.parse(command, expression.operand(node)) == null) {
        return StatusCode.INVALID_TIME_ZONE_DISPLACEMENT;
      }
    }
    return StatusCode.OK;
  }
}
