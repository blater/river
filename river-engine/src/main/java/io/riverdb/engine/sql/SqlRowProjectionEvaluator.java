package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlScalarExpression;
import io.riverdb.storage.heap.HeapRowResult;

/** Owns reusable row projection evaluation and statement-lifetime zone plans. */
final class SqlRowProjectionEvaluator {
  private final SqlProjectionZoneSet zones;
  private final SqlTemporalZonePlan insertZone = new SqlTemporalZonePlan();
  private final SqlMutationEvaluationState inserted;
  private final SqlRowExpressionEvaluator expressions;
  private final SqlJoinProjectionEvaluator joinProjections;
  private final SqlTemporalContext temporal;
  private final SqlExpressionEvaluator columns;
  private final SqlTemporalContext.LongResult current = new SqlTemporalContext.LongResult();
  private BoundSqlStatement bound;

  SqlRowProjectionEvaluator(
      SqlExpressionEvaluator columns, SqlTemporalContext temporal) {
    this(columns, temporal, new SqlSessionShapeBudget(null));
  }

  SqlRowProjectionEvaluator(
      SqlExpressionEvaluator columns,
      SqlTemporalContext temporal,
      SqlSessionShapeBudget budget) {
    expressions = new SqlRowExpressionEvaluator(columns, temporal);
    joinProjections = new SqlJoinProjectionEvaluator(expressions);
    zones = new SqlProjectionZoneSet(budget);
    inserted = new SqlMutationEvaluationState(budget);
    this.temporal = temporal;
    this.columns = columns;
  }

  StatusCode prepare(BoundSqlStatement statement) {
    bound = statement;
    int zoneSlots = Math.max(
        statement.projectionPrograms.count(),
        statement.projectionPrograms.mutationCount());
    StatusCode status = zones.reserve(zoneSlots);
    if (status.isOk()) status = inserted.reserve(statement.projectionPrograms.mutationCount());
    if (!status.isOk()) return status;
    joinProjections.bind(statement, zones);
    SqlCommand command = statement.command;
    SqlBoundProjectionPrograms programs = statement.projectionPrograms;
    for (int projection = 0; projection < programs.count(); projection++) {
      StatusCode projectionStatus = prepare(command, programs, projection);
      if (!projectionStatus.isOk()) return projectionStatus;
    }
    status = StatusCode.OK;
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
        ? insertZone : zones.get(expression);
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
      StatusCode status = zones.prepareMutation(
          temporal,
          command,
          insertZone,
          expression,
          programs.mutationOperand(expression, node));
      if (!status.isOk()) return status;
    }
    if (command.type() != SqlCommandType.INSERT) return StatusCode.OK;
    StatusCode status = expressions.evaluateMutation(
        command, programs, expression, zone, 0, null, bound.table);
    if (status.isOk()) {
      inserted.set(
          expression,
          expressions.resultValue(),
          expressions.resultDescriptor(),
          expressions.resultNull());
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
      StatusCode status = zones.prepareProjection(
          temporal, command, program, programs.operand(program, node));
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  StatusCode project(
      long primaryKey, HeapRowResult source, SqlProjectedRow result) {
    if (bound == null) return StatusCode.CONFLICT;
    result.reset(bound.projectionPrograms.count());
    if (!result.status().isOk()) return result.status();
    for (int projection = 0;
        projection < bound.projectionPrograms.count(); projection++) {
      int raw = bound.projectionPrograms.rawColumn(projection);
      if (raw >= 0) {
        if (columns.isNull(source, bound.table, raw)) result.setNull(projection);
        else if (SqlTypeDescriptor.isWideDecimal(bound.table.typeDescriptor(raw))) {
          result.setDecimal128(
              projection,
              columns.readColumnHigh(primaryKey, source, bound.table, raw),
              columns.readColumn(primaryKey, source, bound.table, raw));
        } else result.setValue(
            projection, columns.readColumn(primaryKey, source, bound.table, raw));
        continue;
      }
      StatusCode status = expressions.evaluate(
          bound.command,
          bound.projectionPrograms,
          projection,
          zones.get(projection),
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
    return SqlBlockRowProjection.project(
        source, result, bound, columns, expressions, zones);
  }

  StatusCode projectBlock(SqlBlockRow source, SqlBlockRow result, int block) {
    if (bound == null || source == null || result == null) return StatusCode.CONFLICT;
    return SqlBlockRowProjection.project(
        source, result, bound, columns, expressions, zones, block);
  }

  StatusCode projectJoin(
      SqlJoinRoleRows rows,
      SqlScanRowResult result) {
    return joinProjections.project(rows, result);
  }

  StatusCode projectJoin(
      SqlJoinRoleRows rows,
      SqlBlockRow result) {
    return joinProjections.project(rows, result);
  }

  StatusCode projectUniversalJoin(
      SqlUniversalJoinRows rows,
      SqlScanRowResult result) {
    return joinProjections.project(rows, result);
  }

  StatusCode projectUniversalJoin(
      SqlUniversalJoinRows rows,
      SqlBlockRow result) {
    return joinProjections.project(rows, result);
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
        zones.get(program),
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
          inserted.value(expression),
          inserted.descriptor(expression),
          inserted.isNull(expression));
      return StatusCode.OK;
    }
    return expressions.evaluateMutation(
        bound.command,
        bound.projectionPrograms,
        expression,
        zones.get(expression),
        primaryKey,
        source,
        bound.table);
  }

  StatusCode evaluateDescriptorMutation(
      int expression, io.riverdb.base.type.SqlValueBuffer source) {
    if (bound == null
        || expression < 0
        || expression >= bound.projectionPrograms.mutationCount()) {
      return StatusCode.CONFLICT;
    }
    return expressions.evaluateDescriptorMutation(
        bound.command,
        bound.projectionPrograms,
        expression,
        zones.get(expression),
        source);
  }

  boolean resultNull() { return expressions.resultNull(); }
  long resultHighValue() { return expressions.resultHighValue(); }
  long resultValue() { return expressions.resultValue(); }
  int resultDescriptor() { return expressions.resultDescriptor(); }
  int resultTextLength() { return expressions.resultTextLength(); }
  char resultTextCharacter(int index) {
    return expressions.resultTextCharacter(index);
  }

  void reset() {
    joinProjections.reset();
    expressions.reset();
    zones.reset();
    inserted.reset();
    insertZone.reset();
    bound = null;
  }
}
