package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommand;

/** Selects a merge stage whose incoming composite already has compatible order. */
final class SqlJoinMergeSelector {
  private final int[] orderedColumns =
      new int[io.riverdb.sql.SqlJoinChain.MAXIMUM_JOIN_ROLES];

  void select(SqlCommand command, SqlBoundJoinContext context) {
    if (command.joinChain().stageCount() == 0
        || !SqlJoinPredicateClassifier.totalJoinOrder(command)) return;
    for (int role = 0; role < orderedColumns.length; role++) orderedColumns[role] = -1;
    orderedColumns[0] = rootOrder(context);
    int selected = context.physicalStrategyStage();
    for (int stage = 0; stage < command.joinChain().stageCount(); stage++) {
      int outerRole = context.accessOuterRole(stage);
      int outerColumn = context.accessOuterColumn(stage);
      int innerColumn = context.accessInnerColumn(stage);
      boolean ordered = outerRole >= 0 && innerColumn >= 0
          && orderedColumns[outerRole] == outerColumn;
      boolean selectableOrder = ordered || stage == 0
          && selected <= 0
          && context.accessPredicate < 0
          && outerRole == 0
          && outerColumn >= 0
          && (outerColumn == 0 || context.table(0).hasIndexOn(outerColumn));
      if (selectableOrder
          && selectable(
              command, context, stage, selected, outerRole, outerColumn)) {
        context.setStrategy(
            stage, SqlJoinStrategy.MERGE, outerRole, outerColumn, innerColumn);
        return;
      }
      if (ordered) orderedColumns[stage + 1] = innerColumn;
    }
  }

  private static boolean selectable(
      SqlCommand command,
      SqlBoundJoinContext context,
      int stage,
      int selected,
      int outerRole,
      int outerColumn) {
    if (selected >= 0 && selected != stage) return false;
    if (selected == stage && context.strategy(stage) != SqlJoinStrategy.HASH) {
      return false;
    }
    return !(stage == 0 && context.accessPredicate >= 0)
        && !(stage == 0
        && command.joinChain().isLeft(stage)
        && context.table(outerRole).isNullable(outerColumn));
  }

  private static int rootOrder(SqlBoundJoinContext context) {
    if (context.accessPredicate < 0) return 0;
    int column = context.predicateColumn;
    return column == 0 || context.table(0).hasIndexOn(column) ? column : 0;
  }
}
