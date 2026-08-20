package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlScalarExpression;
import io.riverdb.storage.heap.HeapRowResult;

/** Evaluates scoped JOIN projection programs into caller-owned row carriers. */
final class SqlJoinProjectionEvaluator {
  private final SqlRowExpressionEvaluator expressions;
  private final SqlPredicateOperand value = new SqlPredicateOperand();
  private BoundSqlStatement bound;
  private SqlTemporalZonePlan[] zones;

  SqlJoinProjectionEvaluator(SqlRowExpressionEvaluator evaluator) {
    expressions = evaluator;
  }

  void bind(BoundSqlStatement statement, SqlTemporalZonePlan[] preparedZones) {
    bound = statement;
    zones = preparedZones;
  }

  StatusCode project(
      long leftKey,
      HeapRowResult leftRow,
      long rightKey,
      HeapRowResult rightRow,
      SqlScanRowResult result) {
    if (bound == null || leftRow == null || result == null) {
      return StatusCode.CONFLICT;
    }
    result.beginProjected(
        leftKey, bound.projectedTypeDescriptors, bound.projectedColumnCount);
    for (int projection = 0;
        projection < bound.projectionPrograms.count(); projection++) {
      StatusCode status = evaluate(
          projection, leftKey, leftRow, rightKey, rightRow);
      if (status.isOk()) status = publish(projection, result);
      if (!status.isOk()) {
        result.reset();
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode evaluate(
      int projection,
      long leftKey,
      HeapRowResult leftRow,
      long rightKey,
      HeapRowResult rightRow) {
    expressions.beginPredicateOperand();
    SqlBoundProjectionPrograms programs = bound.projectionPrograms;
    StatusCode status = StatusCode.OK;
    for (int node = 0;
        status.isOk() && node < programs.nodeCount(projection); node++) {
      int operator = programs.operator(projection, node);
      int scope = programs.scope(projection, node);
      boolean right = scope == SqlBoundBooleanPredicateProgram.SCOPE_RIGHT;
      if (right && rightRow == null && operator == SqlScalarExpression.COLUMN) {
        status = expressions.predicateNullColumnNode(
            programs.descriptor(projection, node));
      } else {
        long key = right ? rightKey : leftKey;
        HeapRowResult row = right ? rightRow : leftRow;
        TableDefinition table = right ? bound.joinTable : bound.table;
        status = expressions.predicateOperandNode(
            bound.command,
            operator,
            programs.operand(projection, node),
            programs.descriptor(projection, node),
            zones[projection],
            key,
            row,
            table,
            null);
      }
    }
    if (!status.isOk()) {
      expressions.reset();
      value.clear();
      return status;
    }
    return expressions.finishPredicateOperand(value);
  }

  private StatusCode publish(int projection, SqlScanRowResult result) {
    if (value.nullValue()) {
      result.setProjectedNull(projection);
      return StatusCode.OK;
    }
    result.setProjectedValue(projection, value.value());
    if (!text()) return StatusCode.OK;
    StatusCode status = result.beginTextAt(projection, value.textLength());
    for (int index = 0; status.isOk() && index < value.textLength(); index++) {
      result.setTextCharacterAt(projection, index, value.textCharacter(index));
    }
    return status;
  }

  private boolean text() {
    return SqlTypeDescriptor.typeId(value.descriptor())
        == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  void reset() {
    value.clear();
    bound = null;
    zones = null;
  }
}
