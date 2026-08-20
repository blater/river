package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlScalarExpression;
import io.riverdb.storage.heap.HeapRowResult;

/** Owns reusable row projection evaluation and statement-lifetime zone plans. */
final class SqlRowProjectionEvaluator {
  private final SqlTemporalZonePlan[] zones =
      new SqlTemporalZonePlan[io.riverdb.engine.relational.TableSchema.MAXIMUM_COLUMNS];
  private final SqlTemporalZonePlan insertZone = new SqlTemporalZonePlan();
  private final long[] insertedValues =
      new long[SqlScalarExpression.MAXIMUM_NODES];
  private final int[] insertedDescriptors =
      new int[SqlScalarExpression.MAXIMUM_NODES];
  private final boolean[] insertedNulls =
      new boolean[SqlScalarExpression.MAXIMUM_NODES];
  private final SqlRowExpressionEvaluator expressions;
  private final SqlJoinProjectionEvaluator joinProjections;
  private final SqlTemporalContext temporal;
  private final SqlExpressionEvaluator columns;
  private final SqlTemporalContext.LongResult current = new SqlTemporalContext.LongResult();
  private BoundSqlStatement bound;

  SqlRowProjectionEvaluator(
      SqlExpressionEvaluator columns, SqlTemporalContext temporal) {
    expressions = new SqlRowExpressionEvaluator(columns, temporal);
    joinProjections = new SqlJoinProjectionEvaluator(expressions);
    this.temporal = temporal;
    this.columns = columns;
    for (int index = 0; index < zones.length; index++) zones[index] = new SqlTemporalZonePlan();
  }

  StatusCode prepare(BoundSqlStatement statement) {
    bound = statement;
    joinProjections.bind(statement, zones);
    SqlCommand command = statement.command;
    SqlBoundProjectionPrograms programs = statement.projectionPrograms;
    for (int projection = 0; projection < programs.count(); projection++) {
      StatusCode status = prepare(command, programs, projection);
      if (!status.isOk()) return status;
    }
    StatusCode status = StatusCode.OK;
    for (int expression = 0;
        status.isOk() && expression < programs.mutationCount();
        expression++) {
      status = prepareMutation(command, programs, expression);
    }
    return status;
  }

  private StatusCode prepareMutation(
      SqlCommand command, SqlBoundProjectionPrograms programs, int expression) {
    SqlTemporalZonePlan zone = command.type() == SqlCommandType.INSERT
        ? insertZone : zones[expression];
    int zoneNodes = 0;
    for (int node = 0; node < programs.mutationNodeCount(expression); node++) {
      int operator = programs.mutationOperator(expression, node);
      if (operator >= SqlScalarExpression.CURRENT_DATE
          && operator <= SqlScalarExpression.LOCALTIMESTAMP) {
        StatusCode status = temporal.currentValue(
            operator, programs.mutationDescriptor(expression, node), current);
        if (!status.isOk()) return status;
      }
      if (operator != SqlScalarExpression.AT_TIME_ZONE) continue;
      if (++zoneNodes > 1) return StatusCode.FEATURE_NOT_SUPPORTED;
      StatusCode status = temporal.prepareZone(
          command,
          programs.mutationOperand(expression, node),
          zone);
      if (!status.isOk()) return status;
    }
    if (command.type() != SqlCommandType.INSERT) return StatusCode.OK;
    StatusCode status = expressions.evaluateMutation(
        command, programs, expression, zone, 0, null, bound.table);
    if (status.isOk()) {
      insertedNulls[expression] = expressions.resultNull();
      insertedValues[expression] = expressions.resultValue();
      insertedDescriptors[expression] = expressions.resultDescriptor();
    }
    return status;
  }

  private StatusCode prepare(
      SqlCommand command, SqlBoundProjectionPrograms programs, int program) {
    int zoneNodes = 0;
    for (int node = 0; node < programs.nodeCount(program); node++) {
      int operator = programs.operator(program, node);
      if (operator >= SqlScalarExpression.CURRENT_DATE
          && operator <= SqlScalarExpression.LOCALTIMESTAMP) {
        StatusCode status = temporal.currentValue(
            operator, programs.descriptor(program, node), current);
        if (!status.isOk()) return status;
      }
      if (operator != SqlScalarExpression.AT_TIME_ZONE) continue;
      if (++zoneNodes > 1) return StatusCode.FEATURE_NOT_SUPPORTED;
      StatusCode status = temporal.prepareZone(
          command, programs.operand(program, node), zones[program]);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode project(
      long primaryKey, HeapRowResult source, SqlProjectedRow result) {
    if (bound == null) return StatusCode.CONFLICT;
    result.reset(bound.projectionPrograms.count());
    for (int projection = 0;
        projection < bound.projectionPrograms.count(); projection++) {
      int raw = bound.projectionPrograms.rawColumn(projection);
      if (raw >= 0) {
        if (columns.isNull(source, bound.table, raw)) result.setNull(projection);
        else result.setValue(projection, columns.readColumn(primaryKey, source, raw));
        continue;
      }
      StatusCode status = expressions.evaluate(
          bound.command,
          bound.projectionPrograms,
          projection,
          zones[projection],
          primaryKey,
          source,
          bound.table,
          result);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode projectBlock(SqlBlockRow source, SqlBlockRow result) {
    if (bound == null || source == null || result == null) return StatusCode.CONFLICT;
    result.reset(bound.projectionPrograms.count());
    for (int projection = 0;
        projection < bound.projectionPrograms.count(); projection++) {
      int raw = bound.projectionPrograms.rawColumn(projection);
      if (raw >= 0) {
        if (source.nullValue(raw)) result.setNull(projection);
        else {
          result.setValue(projection, source.value(raw));
          if (SqlTypeDescriptor.typeId(
                  bound.projectionPrograms.resultDescriptor(projection))
              == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
            result.setText(
                projection, source.text(raw), 0, source.textLength(raw));
          }
        }
        continue;
      }
      StatusCode status = expressions.evaluateBlock(
          bound.command,
          bound.projectionPrograms,
          projection,
          zones[projection],
          source,
          result);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode projectJoin(
      long outerKey,
      HeapRowResult outerRow,
      long innerKey,
      HeapRowResult innerRow,
      SqlScanRowResult result) {
    return joinProjections.project(
        outerKey, outerRow, innerKey, innerRow, result);
  }

  StatusCode evaluateProgram(
      int program, long primaryKey, HeapRowResult source) {
    if (bound == null
        || program < 0
        || program >= bound.projectionPrograms.count()) {
      return StatusCode.CONFLICT;
    }
    return expressions.evaluate(
        bound.command,
        bound.projectionPrograms,
        program,
        zones[program],
        primaryKey,
        source,
        bound.table);
  }

  StatusCode evaluateMutation(
      int expression, long primaryKey, HeapRowResult source) {
    if (bound == null
        || expression < 0
        || expression >= bound.projectionPrograms.mutationCount()) {
      return StatusCode.CONFLICT;
    }
    if (bound.command.type() == SqlCommandType.INSERT) {
      expressions.seedResult(
          insertedValues[expression],
          insertedDescriptors[expression],
          insertedNulls[expression]);
      return StatusCode.OK;
    }
    return expressions.evaluateMutation(
        bound.command,
        bound.projectionPrograms,
        expression,
        zones[expression],
        primaryKey,
        source,
        bound.table);
  }

  boolean resultNull() { return expressions.resultNull(); }
  long resultValue() { return expressions.resultValue(); }
  int resultDescriptor() { return expressions.resultDescriptor(); }
  int resultTextLength() { return expressions.resultTextLength(); }
  char resultTextCharacter(int index) {
    return expressions.resultTextCharacter(index);
  }

  void reset() {
    joinProjections.reset();
    expressions.reset();
    for (int index = 0; index < zones.length; index++) {
      if (zones[index] != null) zones[index].reset();
    }
    insertZone.reset();
    bound = null;
  }
}
