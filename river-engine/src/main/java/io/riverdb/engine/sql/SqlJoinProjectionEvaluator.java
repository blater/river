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
      SqlJoinRoleRows rows,
      SqlScanRowResult result) {
    if (bound == null || rows == null || result == null) {
      return StatusCode.CONFLICT;
    }
    result.beginProjected(
        rows.key(0), bound.projectedTypeDescriptors, bound.projectedColumnCount);
    for (int projection = 0;
        projection < bound.projectionPrograms.count(); projection++) {
      StatusCode status = evaluate(projection, rows);
      if (status.isOk()) status = publish(projection, result);
      if (!status.isOk()) {
        result.reset();
        return status;
      }
    }
    return StatusCode.OK;
  }

  StatusCode project(
      SqlJoinRoleRows rows,
      SqlBlockRow result) {
    if (bound == null || rows == null || result == null) {
      return StatusCode.CONFLICT;
    }
    result.reset(bound.projectedColumnCount);
    for (int projection = 0;
        projection < bound.projectionPrograms.count(); projection++) {
      StatusCode status = evaluate(projection, rows);
      if (status.isOk()) publish(projection, result);
      if (!status.isOk()) {
        result.reset(0);
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode evaluate(int projection, SqlJoinRoleRows rows) {
    expressions.beginPredicateOperand();
    SqlBoundProjectionPrograms programs = bound.projectionPrograms;
    StatusCode status = StatusCode.OK;
    for (int node = 0;
        status.isOk() && node < programs.nodeCount(projection); node++) {
      int operator = programs.operator(projection, node);
      int scope = programs.scope(projection, node);
      HeapRowResult row = rows.row(scope);
      if (row == null && operator == SqlScalarExpression.COLUMN) {
        status = expressions.predicateNullColumnNode(
            programs.descriptor(projection, node));
      } else {
        status = expressions.predicateOperandNode(
            bound.command,
            operator,
            programs.operand(projection, node),
            programs.descriptor(projection, node),
            zones[projection],
            rows.key(scope),
            row,
            rows.table(scope),
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

  private void publish(int projection, SqlBlockRow result) {
    if (value.nullValue()) {
      result.setNull(projection);
      return;
    }
    result.setValue(projection, value.value());
    if (!text()) return;
    char[] target = result.text(projection);
    for (int index = 0; index < value.textLength(); index++) {
      target[index] = value.textCharacter(index);
    }
    result.setTextLength(projection, value.textLength());
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
