package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
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

  StatusCode evaluateHaving(
      SqlCommand command,
      SqlBoundBooleanPredicateProgram programs,
      int leaf,
      int program,
      SqlTemporalZonePlan zone,
      SqlAggregateAccumulatorSet aggregates,
      long groupValue,
      boolean groupNull,
      byte[] groupText,
      int groupTextLength,
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
        result.setValue(aggregates.value(invocation), descriptor, false);
        return StatusCode.OK;
      }
      if (operator == io.riverdb.sql.SqlScalarExpression.GROUP_VALUE
          && io.riverdb.base.type.SqlTypeDescriptor.typeId(descriptor)
              == io.riverdb.base.type.SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        if (groupNull) result.setNull(descriptor);
        else return result.setUtf8(groupText, 0, groupTextLength, descriptor);
        return StatusCode.OK;
      }
    }
    machine.beginHavingPredicateOperand(
        aggregates.values(), aggregates.nulls(), groupValue, groupNull);
    StatusCode status = StatusCode.OK;
    for (int node = 0; status.isOk() && node < count; node++) {
      status = machine.predicateHavingOperandNode(
          command,
          programs.operator(leaf, program, node),
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
      long outerKey,
      HeapRowResult outerRow,
      TableDefinition outerTable,
      long innerKey,
      HeapRowResult innerRow,
      TableDefinition innerTable,
      SqlPredicateOperand result) {
    machine.beginPredicateOperand();
    StatusCode status = StatusCode.OK;
    for (int node = 0;
        status.isOk() && node < programs.nodeCount(leaf, program); node++) {
      int scope = programs.scope(leaf, program, node);
      boolean inner = scope == SqlBoundBooleanPredicateProgram.SCOPE_RIGHT;
      int operator = programs.operator(leaf, program, node);
      if (inner && innerRow == null
          && operator == io.riverdb.sql.SqlScalarExpression.COLUMN) {
        status = machine.predicateNullColumnNode(
            programs.descriptor(leaf, program, node));
      } else {
        status = machine.predicateOperandNode(
            command,
            operator,
            programs.operand(leaf, program, node),
            programs.descriptor(leaf, program, node),
            zone,
            inner ? innerKey : outerKey,
            inner ? innerRow : outerRow,
            inner ? innerTable : outerTable,
            null);
      }
    }
    if (status.isOk()) return machine.finishPredicateOperand(result);
    machine.reset();
    return status;
  }
}
