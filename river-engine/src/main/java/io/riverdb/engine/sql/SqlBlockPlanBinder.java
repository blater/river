package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlScalarExpression;

/** Bottom-up binder for compact cardinality-changing query-block plans. */
final class SqlBlockPlanBinder {
  private final SqlBlockExpressionBinder expressions = new SqlBlockExpressionBinder();
  private final SqlBlockAggregateBinder aggregates = new SqlBlockAggregateBinder(expressions);

  StatusCode bind(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlRowProjectionEvaluator evaluator) {
    StatusCode status = bound.blockPlans.capture(bound.query);
    if (!status.isOk()) return status;
    SqlCommand deepest = bound.blockPlans.command(bound.blockPlans.count() - 1);
    status = session.resolveTable(deepest.tableName(), bound.table);
    if (status.isOk()) physicalSchema(bound);
    SqlBlockSchema child = bound.blockPlans.baseSchema();
    for (int block = bound.blockPlans.count() - 1;
        status.isOk() && block >= 0; block--) {
      status = activate(bound, block, child);
      if (status.isOk() && evaluator != null) status = evaluator.prepare(bound);
      child = bound.blockPlans.schema(block);
    }
    if (status.isOk()) status = activate(bound, 0, bound.blockPlans.schema(1));
    return status;
  }

  StatusCode validateTail(
      BoundSqlStatement bound, int firstBlock) {
    StatusCode status = bound.blockPlans.captureForValidation(bound.query);
    if (!status.isOk() || firstBlock < 0
        || firstBlock >= bound.blockPlans.count()) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    physicalSchema(bound);
    SqlBlockSchema child = bound.blockPlans.baseSchema();
    for (int block = bound.blockPlans.count() - 1;
        status.isOk() && block >= firstBlock; block--) {
      status = activate(bound, block, child);
      child = bound.blockPlans.schema(block);
    }
    return status;
  }

  StatusCode activate(BoundSqlStatement bound, int block, SqlBlockSchema child) {
    SqlCommand source = bound.blockPlans.command(block);
    StatusCode status = bound.command.copyBlockFrom(source);
    if (!status.isOk()) return status;
    resetActive(bound);
    SqlCommandType type = bound.command.type();
    SqlBlockSchema output = bound.blockPlans.schema(block);
    if (SqlBinder.isScalarAggregate(type)) {
      status = aggregates.bind(bound.command, child, output, bound, false);
    } else if (SqlBinder.isGroupAggregate(type)) {
      status = aggregates.bind(bound.command, child, output, bound, true);
    } else {
      status = projections(bound.command, child, output, bound);
    }
    if (status.isOk()) publishOperandSchema(bound, block, child, type);
    if (status.isOk() && block == 0 && bound.command.isOrdered()
        && output.find(bound.command.orderColumnName()) < 0) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return status.isOk() ? predicates(bound.command, child, bound, block) : status;
  }

  private StatusCode projections(
      SqlCommand command,
      SqlBlockSchema child,
      SqlBlockSchema output,
      BoundSqlStatement bound) {
    int count = command.columnCount();
    if (count <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    bound.projectionPrograms.begin(count);
    output.set(count);
    for (int lane = 0; lane < count; lane++) {
      SqlScalarExpression expression = command.projectionExpression(lane);
      StatusCode status = expressions.bind(command, expression, lane, child, bound);
      if (!status.isOk()) return status;
      int descriptor = bound.projectionPrograms.resultDescriptor(lane);
      boolean nullable = expressions.nullable(command, expression, child);
      output.setColumn(lane, command.columnOutputName(lane), descriptor, nullable);
      bound.projectedColumns[lane] = bound.projectionPrograms.rawColumn(lane);
      bound.projectedTypeDescriptors[lane] = descriptor;
    }
    bound.projectedColumnCount = count;
    if (command.type() == SqlCommandType.DISTINCT_SCAN) {
      bound.distinctColumn = bound.projectionPrograms.rawColumn(0);
      bound.sortKeyProjection = bound.distinctColumn < 0 ? 0 : -1;
    }
    return StatusCode.OK;
  }

  private StatusCode predicates(
      SqlCommand command,
      SqlBlockSchema child,
      BoundSqlStatement bound,
      int block) {
    for (int predicate = 0; predicate < command.predicateCount(); predicate++) {
      if (command.predicateExpression(predicate) != null) {
        if (block > 0) return StatusCode.FEATURE_NOT_SUPPORTED;
        bound.projectionPrograms.beginPredicate();
        StatusCode status = expressions.bind(
            command,
            command.predicateExpression(predicate),
            SqlBoundProjectionPrograms.PREDICATE_LANE,
            child,
            bound);
        if (!status.isOk()) return status;
        int descriptor = bound.projectionPrograms.resultDescriptor(
            SqlBoundProjectionPrograms.PREDICATE_LANE);
        SqlComparison comparison = command.comparison(predicate);
        if (SqlTypeDescriptor.typeId(descriptor)
                == SqlTypeDescriptor.TYPE_ID_VARCHAR
            && (comparison == SqlComparison.HALF_OPEN_RANGE
                || comparison == SqlComparison.IN
                || comparison == SqlComparison.NOT_IN)) {
          return StatusCode.FEATURE_NOT_SUPPORTED;
        }
        bound.predicateColumns[predicate] = SqlBoundProjectionPrograms.COMPUTED_PROJECTION;
        bound.blockPredicateRightColumns[predicate] = -1;
        continue;
      }
      int left = child.find(command.predicateColumnName(predicate));
      if (left < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      bound.predicateColumns[predicate] = left;
      bound.blockPredicateRightColumns[predicate] = -1;
      if (command.isNullPredicate(predicate)) continue;
      int right = command.predicateTypeDescriptor(predicate);
      if (command.isColumnPredicate(predicate)) {
        int column = child.find(command.predicateValueColumnName(predicate));
        if (column < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
        bound.blockPredicateRightColumns[predicate] = column;
        right = child.descriptor(column);
      }
      if (!SqlTypeDescriptor.canCompare(child.descriptor(left), right)) {
        return StatusCode.DATATYPE_MISMATCH;
      }
      SqlComparison comparison = command.comparison(predicate);
      if (SqlTypeDescriptor.typeId(child.descriptor(left))
              == SqlTypeDescriptor.TYPE_ID_BOOLEAN
          && comparison != SqlComparison.EQUAL
          && comparison != SqlComparison.NOT_EQUAL
          && comparison != SqlComparison.IN
          && comparison != SqlComparison.NOT_IN) {
        return StatusCode.DATATYPE_MISMATCH;
      }
    }
    bound.predicateCount = command.predicateCount();
    return StatusCode.OK;
  }

  private void physicalSchema(BoundSqlStatement bound) {
    SqlBlockSchema physical = bound.blockPlans.baseSchema();
    physical.set(bound.table.columnCount());
    for (int column = 0; column < bound.table.columnCount(); column++) {
      physical.setColumn(
          column,
          bound.table.columnName(column),
          bound.table.typeDescriptor(column),
          bound.table.isNullable(column));
    }
  }

  private void publishOperandSchema(
      BoundSqlStatement bound,
      int block,
      SqlBlockSchema child,
      SqlCommandType type) {
    SqlBlockSchema operands = bound.blockPlans.operandSchema(block);
    if (!SqlBinder.isScalarAggregate(type) && !SqlBinder.isGroupAggregate(type)) {
      operands.copyFrom(bound.blockPlans.schema(block));
      return;
    }
    operands.set(bound.projectionPrograms.count());
    for (int lane = 0; lane < bound.projectionPrograms.count(); lane++) {
      operands.setColumn(
          lane,
          "",
          bound.projectionPrograms.resultDescriptor(lane),
          expressions.nullable(
              bound.command,
              bound.command.aggregateOperandExpression(lane),
              child));
    }
  }

  private static void resetActive(BoundSqlStatement bound) {
    bound.projectionPrograms.reset();
    bound.aggregates.reset();
    bound.havingPrograms.reset();
    bound.projectedColumnCount = 0;
    bound.predicateCount = 0;
    bound.groupColumn = -1;
    bound.groupAggregateColumn = -1;
    bound.distinctColumn = -1;
    bound.orderColumn = -1;
    bound.sortKeyProjection = -1;
  }
}
