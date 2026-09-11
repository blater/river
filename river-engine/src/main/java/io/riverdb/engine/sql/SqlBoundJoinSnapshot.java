package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlJoinChain;

/** Stable join access and estimate snapshot retained for one block plan. */
final class SqlBoundJoinSnapshot {
  private int joinBlock = -1;
  private int joinRootAccessColumn = -1;
  private int joinStageCount;
  private final int[] joinRightColumns = new int[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final byte[] joinAccessKinds = new byte[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final byte[] joinStrategies = new byte[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final long[] statisticsEpochs = new long[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final long[] statisticsRows = new long[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final boolean[] statisticsSampled = new boolean[SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final long[] estimatedRows = new long[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private boolean estimatesAvailable;

  void capture(
      int block,
      SqlCommand command,
      SqlBoundJoinContext context) {
    joinBlock = block;
    joinRootAccessColumn = context.strategy(0) == SqlJoinStrategy.MERGE
        ? context.strategyOuterColumn(0)
        : context.accessPredicate >= 0
            && (context.predicateColumn == 0
                || context.table(0).hasIndexOn(context.predicateColumn))
            ? context.predicateColumn : -1;
    joinStageCount = command.joinChain().stageCount();
    estimatesAvailable = context.estimatesAvailable();
    if (estimatesAvailable) {
      for (int role = 0; role <= joinStageCount; role++) {
        statisticsEpochs[role] = context.statistics(role).epoch();
        statisticsRows[role] = context.statistics(role).rowCount();
        statisticsSampled[role] = context.statistics(role).sampled();
      }
    }
    for (int stage = 0; stage < joinStageCount; stage++) {
      int strategy = context.strategy(stage);
      int right = strategy != SqlJoinStrategy.NESTED_LOOP
          ? context.strategyInnerColumn(stage) : context.accessInnerColumn(stage);
      joinRightColumns[stage] = right;
      boolean indexed = strategy != SqlJoinStrategy.HASH && right >= 0
          && (right == 0 || context.table(stage + 1).hasIndexOn(right));
      boolean unique = strategy != SqlJoinStrategy.MERGE && indexed
          && (right == 0 || context.table(stage + 1).hasUniqueIndexOn(right));
      int access = strategy == SqlJoinStrategy.MERGE && !indexed ? 3
          : unique ? 2 : (indexed
              && !(strategy == SqlJoinStrategy.MERGE && right == 0) ? 1 : 0);
      joinAccessKinds[stage] = (byte) access;
      joinStrategies[stage] = (byte) strategy;
      estimatedRows[stage] = context.estimatedRows(stage);
    }
  }

  void reset() {
    joinBlock = -1;
    joinRootAccessColumn = -1;
    joinStageCount = 0;
    for (int stage = 0; stage < joinRightColumns.length; stage++) {
      joinRightColumns[stage] = -1;
      joinAccessKinds[stage] = 0;
      joinStrategies[stage] = 0;
      estimatedRows[stage] = 0;
    }
    for (int role = 0; role < statisticsEpochs.length; role++) {
      statisticsEpochs[role] = 0;
      statisticsRows[role] = 0;
      statisticsSampled[role] = false;
    }
    estimatesAvailable = false;
  }

  int rootAccessColumn(int block) {
    return block == joinBlock ? joinRootAccessColumn : -1;
  }
  int stageCount(int block) { return block == joinBlock ? joinStageCount : 0; }
  int rightColumn(int block, int stage) {
    return block == joinBlock ? joinRightColumns[stage] : -1;
  }
  int accessKind(int block, int stage) {
    return block == joinBlock ? joinAccessKinds[stage] : 0;
  }
  int strategy(int block, int stage) {
    return block == joinBlock
        ? Byte.toUnsignedInt(joinStrategies[stage]) : SqlJoinStrategy.NESTED_LOOP;
  }
  boolean estimatesAvailable(int block) {
    return block == joinBlock && estimatesAvailable;
  }
  long statisticsEpoch(int block, int role) {
    return block == joinBlock ? statisticsEpochs[role] : 0;
  }
  long statisticsRows(int block, int role) {
    return block == joinBlock ? statisticsRows[role] : 0;
  }
  boolean statisticsSampled(int block, int role) {
    return block == joinBlock && statisticsSampled[role];
  }
  long estimatedRows(int block, int stage) {
    return block == joinBlock ? estimatedRows[stage] : 0;
  }
}
