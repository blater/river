package io.riverdb.engine.sql;

import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlComparison;

/** Separates semantic cardinality policy from the bound physical access shape. */
final class SqlPhysicalStepClassifier {
  private SqlPhysicalStepClassifier() { }

  static SqlPhysicalStepKind classify(
      BoundSqlStatement bound, int semanticAction) {
    if (semanticAction == TransactionProgramAction.COMMAND) {
      return SqlPhysicalStepKind.COMMAND;
    }
    if (isAggregate(bound.command.type())) {
      return SqlPhysicalStepKind.AGGREGATE;
    }
    if (semanticAction == TransactionProgramAction.ROW_SET) {
      return SqlPhysicalStepKind.ROW_SET;
    }
    return classifySingleton(bound);
  }

  /** Classifies the singleton access without changing its cardinality policy. */
  static SqlPhysicalStepKind classifySingleton(BoundSqlStatement bound) {
    if (!pointAccess(bound)) {
      return SqlPhysicalStepKind.SCAN_SINGLETON;
    }
    return bound.predicateColumn == 0
        ? SqlPhysicalStepKind.POINT_PRIMARY
        : SqlPhysicalStepKind.POINT_UNIQUE;
  }

  private static boolean pointAccess(BoundSqlStatement bound) {
    if (bound == null || !selectCommand(bound.command.type())) return false;
    if (bound.executableQuery.edgeCount() != 0 || bound.expandedView) return false;
    if (bound.pointTextColumn >= 0 || bound.accessPredicate < 0
        || bound.accessComparison != SqlComparison.EQUAL) return false;
    if (bound.predicateColumn == 0) return true;
    return bound.predicateColumn > 0
        && bound.table.hasUniqueIndexOn(bound.predicateColumn)
        && !bound.table.isVarchar(bound.predicateColumn);
  }

  private static boolean isAggregate(SqlCommandType type) {
    return type != null
        && (SqlBinder.isScalarAggregate(type) || SqlBinder.isGroupAggregate(type));
  }

  private static boolean selectCommand(SqlCommandType type) {
    return type == SqlCommandType.SELECT || type == SqlCommandType.SCAN;
  }
}
