package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;
import io.riverdb.storage.heap.HeapRowResult;

/** Traverses one bound predicate operand through the shared scalar machine. */
final class SqlPredicateOperandEvaluator {
  private final SqlRowExpressionEvaluator machine;

  SqlPredicateOperandEvaluator(
      SqlExpressionEvaluator columns, SqlTemporalContext temporal) {
    machine = new SqlRowExpressionEvaluator(columns, temporal);
  }

  void reset() {
    machine.reset();
  }

  StatusCode evaluate(
      SqlCommand command,
      SqlBoundBooleanPredicateProgram programs,
      int leaf,
      int program,
      SqlTemporalZonePlan zone,
      long primaryKey,
      HeapRowResult source,
      TableDefinition table,
      SqlBlockRow block,
      SqlPredicateOperand result) {
    machine.beginPredicateOperand();
    StatusCode status = StatusCode.OK;
    for (int node = 0;
        status.isOk() && node < programs.nodeCount(leaf, program); node++) {
      status = machine.predicateOperandNode(
          command,
          programs.operator(leaf, program, node),
          programs.operandHigh(leaf, program, node),
          programs.operand(leaf, program, node),
          programs.descriptor(leaf, program, node),
          zone,
          primaryKey,
          source,
          table,
          block);
    }
    if (status.isOk()) return machine.finishPredicateOperand(result);
    machine.reset();
    return status;
  }

  StatusCode evaluateNested(
      SqlCommand command,
      SqlBoundBooleanPredicateProgram programs,
      int leaf,
      int program,
      SqlTemporalZonePlan zone,
      SqlNestedRowProvider rows,
      SqlPredicateOperand result) {
    machine.beginPredicateOperand();
    StatusCode status = StatusCode.OK;
    for (int node = 0;
        status.isOk() && node < programs.nodeCount(leaf, program); node++) {
      int scope = programs.scope(leaf, program, node);
      int block = SqlNestedRowProvider.block(scope);
      int role = SqlNestedRowProvider.role(scope);
      int operator = programs.operator(leaf, program, node);
      if (operator == SqlScalarExpression.COLUMN) {
        status = SqlNestedColumnValue.evaluate(
            machine,
            command,
            zone,
            rows,
            block,
            role,
            (int) programs.operand(leaf, program, node),
            programs.descriptor(leaf, program, node));
      } else {
        status = machine.predicateOperandNode(
            command,
            operator,
            programs.operandHigh(leaf, program, node),
            programs.operand(leaf, program, node),
            programs.descriptor(leaf, program, node),
            zone,
            0,
            null,
            null,
            null);
      }
    }
    if (status.isOk()) return machine.finishPredicateOperand(result);
    machine.reset();
    return status;
  }

  StatusCode evaluateHaving(
      SqlCommand command,
      SqlBoundBooleanPredicateProgram programs,
      int leaf,
      int program,
      SqlTemporalZonePlan zone,
      SqlAggregateAccumulatorSet aggregates,
      SqlHavingGroup group,
      SqlPredicateOperand result) {
    int count = programs.nodeCount(leaf, program);
    if (count == 1) {
      int operator = programs.operator(leaf, program, 0);
      int descriptor = programs.descriptor(leaf, program, 0);
      if (operator == io.riverdb.sql.SqlScalarExpression.AGGREGATE_VALUE) {
        int invocation = (int) programs.operand(leaf, program, 0);
        if (aggregates.nullValue(invocation)) {
          result.setNull(descriptor);
          return StatusCode.OK;
        }
        if (io.riverdb.base.type.SqlTypeDescriptor.typeId(descriptor)
            == io.riverdb.base.type.SqlTypeDescriptor.TYPE_ID_VARCHAR) {
          return result.setUtf8(
              aggregates.text(),
              aggregates.textOffset(invocation),
              aggregates.textLength(invocation),
              descriptor);
        }
        result.setValue(
            aggregates.highValue(invocation),
            aggregates.value(invocation), descriptor, false);
        return StatusCode.OK;
      }
      if (operator == io.riverdb.sql.SqlScalarExpression.GROUP_VALUE) {
        return group.publish((int) programs.operand(leaf, program, 0), descriptor, result);
      }
    }
    machine.beginHavingPredicateOperand(
        aggregates.highs(), aggregates.values(), aggregates.nulls(), group);
    StatusCode status = StatusCode.OK;
    for (int node = 0; status.isOk() && node < count; node++) {
      status = machine.predicateHavingOperandNode(
          command,
          programs.operator(leaf, program, node),
          programs.operandHigh(leaf, program, node),
          programs.operand(leaf, program, node),
          programs.descriptor(leaf, program, node),
          zone);
    }
    if (status.isOk()) return machine.finishPredicateOperand(result);
    machine.reset();
    return status;
  }

  StatusCode evaluateJoin(
      SqlCommand command,
      SqlBoundBooleanPredicateProgram programs,
      int leaf,
      int program,
      SqlTemporalZonePlan zone,
      SqlJoinRoleRows rows,
      SqlPredicateOperand result) {
    machine.beginPredicateOperand();
    StatusCode status = StatusCode.OK;
    for (int node = 0;
        status.isOk() && node < programs.nodeCount(leaf, program); node++) {
      int scope = programs.scope(leaf, program, node);
      int operator = programs.operator(leaf, program, node);
      HeapRowResult row = rows.row(scope);
      if (row == null
          && operator == io.riverdb.sql.SqlScalarExpression.COLUMN) {
        status = machine.predicateNullColumnNode(
            programs.descriptor(leaf, program, node));
      } else {
        status = machine.predicateOperandNode(
            command,
            operator,
            programs.operandHigh(leaf, program, node),
            programs.operand(leaf, program, node),
            programs.descriptor(leaf, program, node),
            zone,
            rows.key(scope),
            row,
            rows.table(scope),
            null);
      }
    }
    if (status.isOk()) return machine.finishPredicateOperand(result);
    machine.reset();
    return status;
  }

  StatusCode evaluateUniversalJoin(
      SqlCommand command,
      SqlBoundBooleanPredicateProgram programs,
      int leaf,
      int program,
      SqlTemporalZonePlan zone,
      SqlUniversalJoinRows rows,
      SqlPredicateOperand result) {
    machine.beginPredicateOperand();
    StatusCode status = StatusCode.OK;
    for (int node = 0;
        status.isOk() && node < programs.nodeCount(leaf, program); node++) {
      int scope = programs.scope(leaf, program, node);
      int operator = programs.operator(leaf, program, node);
      int descriptor = programs.descriptor(leaf, program, node);
      if (operator == SqlScalarExpression.COLUMN) {
        status = rows.nullRole(scope)
            ? machine.predicateNullColumnNode(descriptor)
            : machine.predicateBlockColumnNode(
                rows.row(scope), (int) programs.operand(leaf, program, node), descriptor);
      } else {
        status = machine.predicateOperandNode(
            command,
            operator,
            programs.operandHigh(leaf, program, node),
            programs.operand(leaf, program, node),
            descriptor,
            zone,
            0,
            null,
            null,
            null);
      }
    }
    if (status.isOk()) return machine.finishPredicateOperand(result);
    machine.reset();
    return status;
  }
}
