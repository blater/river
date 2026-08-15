package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlQuery;
import io.riverdb.sql.SqlScalarExpression;

/** Shared admission policy for one durable, executable view definition. */
final class SqlStoredViewPolicy {
  private SqlStoredViewPolicy() {}

  static StatusCode validate(SqlCommand command, SqlQuery query) {
    if (query.hasNestedTopology() || query.isExplain()
        || command.hasComputedPredicate()) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    if (query.sourcePlanDepth() >= SqlQuery.MAXIMUM_QUERY_BLOCKS) {
      return StatusCode.QUERY_TOO_COMPLEX;
    }
    if (command.type() == SqlCommandType.JOIN_SCAN
        && SqlRowProjectionBinder.hasComputed(command)) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    if (!shape(command)
        || !hasUniqueOutputNames(command)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }

  static StatusCode validateExpanded(SqlCommand command) {
    if (command.hasComputedPredicate()) return StatusCode.FEATURE_NOT_SUPPORTED;
    return shape(command) && hasUniqueOutputNames(command)
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private static boolean shape(SqlCommand command) {
    return admittedType(command.type())
        && !command.isSelectAll()
        && command.columnCount() > 0
        && !command.isOrdered()
        && command.rowLimit() == Long.MAX_VALUE
        && !command.hasDisjunction();
  }

  static StatusCode validateZones(
      SqlCommand command, SqlQuery query, SqlTemporalZoneNames zones) {
    for (int block = 0; block < Math.max(1, query.blockCount()); block++) {
      SqlCommand current = query.blockCount() == 0 ? command : query.block(block);
      StatusCode status = validateCommandZones(current, zones);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  static StatusCode validateZones(
      SqlQuery query, int firstBlock, SqlTemporalZoneNames zones) {
    if (firstBlock < 0 || firstBlock >= query.blockCount()) {
      return StatusCode.CORRUPTION;
    }
    for (int block = firstBlock; block < query.blockCount(); block++) {
      StatusCode status = validateCommandZones(query.block(block), zones);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private static StatusCode validateCommandZones(
      SqlCommand command, SqlTemporalZoneNames zones) {
    for (int projection = 0; projection < command.columnCount(); projection++) {
      SqlScalarExpression expression = command.projectionExpression(projection);
      StatusCode status = validateExpressionZones(command, expression, zones);
      if (!status.isOk()) return status;
    }
    for (int lane = 0; lane < SqlCommand.MAXIMUM_COLUMNS; lane++) {
      SqlScalarExpression expression = command.aggregateOperandExpression(lane);
      if (expression == null || !expression.isAvailable()) continue;
      StatusCode status = validateExpressionZones(command, expression, zones);
      if (!status.isOk()) return status;
    }
    for (int predicate = 0; predicate < command.havingPredicateCount(); predicate++) {
      int zoneNodes = 0;
      for (int node = 0; node < command.havingNodeCount(predicate); node++) {
        if (command.havingOperator(predicate, node) == SqlScalarExpression.AT_TIME_ZONE) {
          if (++zoneNodes > 1) return StatusCode.FEATURE_NOT_SUPPORTED;
          if (zones.parse(command, command.havingOperand(predicate, node)) == null) {
            return StatusCode.INVALID_TIME_ZONE_DISPLACEMENT;
          }
        }
      }
    }
    return StatusCode.OK;
  }

  private static StatusCode validateExpressionZones(
      SqlCommand command,
      SqlScalarExpression expression,
      SqlTemporalZoneNames zones) {
    if (expression == null || !expression.isAvailable()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int zoneNodes = 0;
    for (int node = 0; node < expression.nodeCount(); node++) {
      if (expression.operator(node) == SqlScalarExpression.AT_TIME_ZONE) {
        if (++zoneNodes > 1) return StatusCode.FEATURE_NOT_SUPPORTED;
        if (zones.parse(command, expression.operand(node)) == null) {
          return StatusCode.INVALID_TIME_ZONE_DISPLACEMENT;
        }
      }
    }
    return StatusCode.OK;
  }

  private static boolean admittedType(SqlCommandType type) {
    if (type == SqlCommandType.SCAN || type == SqlCommandType.SELECT) return true;
    return type == SqlCommandType.DISTINCT_SCAN
        || type == SqlCommandType.COUNT
        || type == SqlCommandType.COUNT_VALUE
        || type == SqlCommandType.SUM
        || type == SqlCommandType.AVG
        || type == SqlCommandType.MIN
        || type == SqlCommandType.MAX
        || type == SqlCommandType.GROUP_COUNT
        || type == SqlCommandType.GROUP_COUNT_VALUE
        || type == SqlCommandType.GROUP_SUM
        || type == SqlCommandType.GROUP_AVG
        || type == SqlCommandType.GROUP_MIN
        || type == SqlCommandType.GROUP_MAX;
  }

  private static boolean hasUniqueOutputNames(SqlCommand command) {
    for (int index = 0; index < command.columnCount(); index++) {
      CharSequence name = command.columnOutputName(index);
      if (name.length() == 0) return false;
      for (int previous = 0; previous < index; previous++) {
        if (SqlBindingNames.same(name, command.columnOutputName(previous))) {
          return false;
        }
      }
    }
    return true;
  }
}
