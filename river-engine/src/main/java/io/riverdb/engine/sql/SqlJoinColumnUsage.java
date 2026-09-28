package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlScalarExpression;

/** Prepares descriptor-role demand from bound JOIN expressions and predicates. */
final class SqlJoinColumnUsage {
  private SqlJoinColumnUsage() { }

  static StatusCode prepare(
      BoundSqlStatement bound, SqlCommand command,
      SqlBoundJoinContext context, SqlBoundBooleanPredicateProgram where,
      SqlUniversalJoinRows rows) {
    if (bound.executableQuery.edgeCount() > 0
        || command.type() != SqlCommandType.JOIN_SCAN
        || command.isSelectAll()
        || command.orderBy().count() > 0
        || command.grouping().count() > 0
        || command.booleanHavingPredicates().leafCount() > 0
        || where == null
        || bound.projectionPrograms.count() < command.columnCount()) {
      rows.selectAll();
      return StatusCode.OK;
    }
    StatusCode status = rows.selectNone();
    if (!status.isOk()) return status;
    if (!markProjections(bound.projectionPrograms, context, rows)
        || !markPredicate(where, context, rows)) {
      rows.selectAll();
      return StatusCode.OK;
    }
    for (int stage = 0; stage < command.joinChain().stageCount(); stage++) {
      if (!markPredicate(context.onBoolean(stage), context, rows)) {
        rows.selectAll();
        return StatusCode.OK;
      }
      int outerRole = context.accessOuterRole(stage);
      int outerColumn = context.accessOuterColumn(stage);
      int innerColumn = context.accessInnerColumn(stage);
      if (outerRole >= 0 && !mark(context, rows, outerRole, outerColumn)
          || innerColumn >= 0 && !mark(context, rows, stage + 1, innerColumn)) {
        rows.selectAll();
        return StatusCode.OK;
      }
    }
    return StatusCode.OK;
  }

  private static boolean markProjections(
      SqlBoundProjectionPrograms programs,
      SqlBoundJoinContext context, SqlUniversalJoinRows rows) {
    for (int projection = 0; projection < programs.count(); projection++) {
      for (int node = 0; node < programs.nodeCount(projection); node++) {
        if (programs.operator(projection, node) != SqlScalarExpression.COLUMN) continue;
        if (!mark(context, rows, programs.scope(projection, node),
            (int) programs.operand(projection, node))) return false;
      }
    }
    return true;
  }

  private static boolean markPredicate(
      SqlBoundBooleanPredicateProgram predicates,
      SqlBoundJoinContext context, SqlUniversalJoinRows rows) {
    if (predicates == null) return false;
    for (int leaf = 0; leaf < predicates.leafCount(); leaf++) {
      for (int program = SqlBooleanPredicateProgram.PROGRAM_LEFT;
          program <= SqlBooleanPredicateProgram.PROGRAM_UPPER; program++) {
        for (int node = 0; node < predicates.nodeCount(leaf, program); node++) {
          if (predicates.operator(leaf, program, node) != SqlScalarExpression.COLUMN) continue;
          if (!mark(context, rows, predicates.scope(leaf, program, node),
              (int) predicates.operand(leaf, program, node))) return false;
        }
      }
    }
    return true;
  }

  private static boolean mark(
      SqlBoundJoinContext context, SqlUniversalJoinRows rows,
      int scope, int column) {
    int role = context.localRole(scope);
    if (role < 0 || role >= context.roleCount || context.table(role) == null
        || column < 0 || column >= context.table(role).columnCount()) return false;
    rows.select(role, column);
    return true;
  }
}
