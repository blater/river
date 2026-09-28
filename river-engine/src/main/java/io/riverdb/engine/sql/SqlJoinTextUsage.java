package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlScalarExpression;

/** Conservative proof that a JOIN block never reads base-row text. */
final class SqlJoinTextUsage {
  private SqlJoinTextUsage() { }

  static boolean requiresText(
      BoundSqlStatement bound,
      SqlCommand command,
      SqlBoundJoinContext context,
      SqlBoundBooleanPredicateProgram where) {
    if (bound.executableQuery.edgeCount() > 0
        || command.type() != SqlCommandType.JOIN_SCAN
        || command.isSelectAll()
        || command.orderBy().count() > 0
        || command.grouping().count() > 0
        || command.aggregates().invocationCount() > 0
        || command.booleanHavingPredicates().leafCount() > 0
        || where == null
        || bound.projectionPrograms.count() < command.columnCount()) {
      return true;
    }
    for (int projection = 0; projection < bound.projectionPrograms.count(); projection++) {
      for (int node = 0; node < bound.projectionPrograms.nodeCount(projection); node++) {
        if (textColumn(
            bound.projectionPrograms.operator(projection, node),
            bound.projectionPrograms.descriptor(projection, node))) return true;
      }
    }
    if (textColumn(where)) return true;
    for (int stage = 0; stage < command.joinChain().stageCount(); stage++) {
      if (textColumn(context.onBoolean(stage))) return true;
    }
    return false;
  }

  private static boolean textColumn(SqlBoundBooleanPredicateProgram predicates) {
    if (predicates == null) return true;
    for (int leaf = 0; leaf < predicates.leafCount(); leaf++) {
      for (int program = SqlBooleanPredicateProgram.PROGRAM_LEFT;
          program <= SqlBooleanPredicateProgram.PROGRAM_UPPER; program++) {
        for (int node = 0; node < predicates.nodeCount(leaf, program); node++) {
          if (textColumn(
              predicates.operator(leaf, program, node),
              predicates.descriptor(leaf, program, node))) return true;
        }
      }
    }
    return false;
  }

  private static boolean textColumn(int operator, int descriptor) {
    return operator == SqlScalarExpression.COLUMN
        && (descriptor == 0
            || SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR);
  }
}
