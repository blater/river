package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommand;

/** Selects a merge stage whose incoming composite already has compatible order. */
final class SqlJoinMergeSelector {
  private final int[] orderedColumns =
      new int[io.riverdb.sql.SqlJoinChain.MAXIMUM_JOIN_ROLES];

  void select(SqlCommand command, BoundSqlStatement bound) {
    if (command.joinChain().stageCount() == 0
        || !SqlJoinPredicateClassifier.totalJoinOrder(command)) return;
    for (int role = 0; role < orderedColumns.length; role++) orderedColumns[role] = -1;
    orderedColumns[0] = rootOrder(bound);
    int selected = bound.physicalJoinStrategyStage();
    for (int stage = 0; stage < command.joinChain().stageCount(); stage++) {
      int outerRole = bound.joinAccessOuterRole(stage);
      int outerColumn = bound.joinAccessOuterColumn(stage);
      int innerColumn = bound.joinAccessInnerColumn(stage);
      boolean ordered = outerRole >= 0 && innerColumn >= 0
          && orderedColumns[outerRole] == outerColumn;
      boolean selectableOrder = ordered || stage == 0
          && selected <= 0
          && bound.accessPredicate < 0
          && outerRole == 0
          && outerColumn >= 0
          && (outerColumn == 0 || bound.table.hasIndexOn(outerColumn));
      if (selectableOrder
          && selectable(bound, stage, selected, outerRole, outerColumn)) {
        bound.setJoinStrategy(
            stage, SqlJoinStrategy.MERGE, outerRole, outerColumn, innerColumn);
        return;
      }
      if (ordered) orderedColumns[stage + 1] = innerColumn;
    }
  }

  private static boolean selectable(
      BoundSqlStatement bound,
      int stage,
      int selected,
      int outerRole,
      int outerColumn) {
    if (selected >= 0 && selected != stage) return false;
    if (selected == stage && bound.joinStrategy(stage) != SqlJoinStrategy.HASH) {
      return false;
    }
    return !(stage == 0 && bound.accessPredicate >= 0)
        && !(stage == 0
        && bound.command.joinChain().isLeft(stage)
        && bound.joinRole(outerRole).isNullable(outerColumn));
  }

  private static int rootOrder(BoundSqlStatement bound) {
    if (bound.accessPredicate < 0) return 0;
    int column = bound.predicateColumn;
    return column == 0 || bound.table.hasIndexOn(column) ? column : 0;
  }
}
