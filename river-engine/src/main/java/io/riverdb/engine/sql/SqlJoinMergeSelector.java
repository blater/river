package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommand;

/** Selects the first conservative index-ordered equality merge stage. */
final class SqlJoinMergeSelector {
  void select(SqlCommand command, BoundSqlStatement bound) {
    if (command.joinChain().stageCount() == 0
        || bound.hasPhysicalJoinStrategy()
        || bound.accessPredicate >= 0
        || !SqlJoinPredicateClassifier.totalJoinOrder(command)) return;
    int stage = 0;
    int outerRole = bound.joinAccessOuterRole(stage);
    int outerColumn = bound.joinAccessOuterColumn(stage);
    int innerColumn = bound.joinAccessInnerColumn(stage);
    if (outerRole != 0 || outerColumn < 0 || innerColumn < 0
        || command.joinChain().isLeft(stage)
            && bound.joinRole(0).isNullable(outerColumn)
        || !ordered(bound.joinRole(0), outerColumn)
        || !ordered(bound.joinRole(1), innerColumn)) return;
    bound.setJoinStrategy(
        stage, SqlJoinStrategy.MERGE, outerRole, outerColumn, innerColumn);
  }

  private static boolean ordered(
      io.riverdb.engine.relational.TableDefinition table, int column) {
    return column == 0 || table.hasIndexOn(column);
  }
}
