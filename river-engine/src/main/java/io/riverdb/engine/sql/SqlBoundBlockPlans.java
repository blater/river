package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;

/** Statement-owned query-block references and stable typed stage schemas. */
final class SqlBoundBlockPlans {
  private final SqlCommand[] commands = new SqlCommand[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final SqlBlockSchema[] schemas = new SqlBlockSchema[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final SqlBlockSchema[] operandSchemas =
      new SqlBlockSchema[SqlQuery.MAXIMUM_QUERY_BLOCKS];
  private final SqlBlockSchema baseSchema = new SqlBlockSchema();
  private int joinBlock = -1;
  private int joinRootAccessColumn = -1;
  private int joinStageCount;
  private final int[] joinRightColumns =
      new int[io.riverdb.sql.SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final byte[] joinAccessKinds =
      new byte[io.riverdb.sql.SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final byte[] joinStrategies =
      new byte[io.riverdb.sql.SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final long[] joinStatisticsEpochs =
      new long[io.riverdb.sql.SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final long[] joinStatisticsRows =
      new long[io.riverdb.sql.SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final boolean[] joinStatisticsSampled =
      new boolean[io.riverdb.sql.SqlJoinChain.MAXIMUM_JOIN_ROLES];
  private final long[] joinEstimatedRows =
      new long[io.riverdb.sql.SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private boolean joinEstimatesAvailable;
  private int count;

  SqlBoundBlockPlans() {
    for (int index = 0; index < schemas.length; index++) {
      schemas[index] = new SqlBlockSchema();
      operandSchemas[index] = new SqlBlockSchema();
    }
  }

  StatusCode capture(SqlQuery query) {
    return capture(query, true);
  }

  StatusCode captureForValidation(SqlQuery query) {
    return capture(query, false);
  }

  private StatusCode capture(SqlQuery query, boolean requirePipeline) {
    reset();
    int sourceBlocks = query == null ? 0 : query.sourceBlockCount();
    if (query == null || requirePipeline && !query.isBlockPipeline()
        || sourceBlocks < 2 || sourceBlocks > commands.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    count = sourceBlocks;
    for (int index = 0; index < count; index++) {
      commands[index] = query.block(index);
    }
    return StatusCode.OK;
  }

  void reset() {
    for (int index = 0; index < count; index++) {
      commands[index] = null;
      schemas[index].reset();
      operandSchemas[index].reset();
    }
    count = 0;
    baseSchema.reset();
    joinBlock = -1;
    joinRootAccessColumn = -1;
    joinStageCount = 0;
    for (int stage = 0; stage < joinRightColumns.length; stage++) {
      joinRightColumns[stage] = -1;
      joinAccessKinds[stage] = 0;
      joinStrategies[stage] = 0;
      joinEstimatedRows[stage] = 0;
    }
    for (int role = 0; role < joinStatisticsEpochs.length; role++) {
      joinStatisticsEpochs[role] = 0;
      joinStatisticsRows[role] = 0;
      joinStatisticsSampled[role] = false;
    }
    joinEstimatesAvailable = false;
  }

  int count() { return count; }
  SqlCommand command(int block) { return commands[block]; }
  SqlBlockSchema schema(int block) { return schemas[block]; }
  SqlBlockSchema operandSchema(int block) { return operandSchemas[block]; }
  SqlBlockSchema baseSchema() { return baseSchema; }

  void setJoinAccess(
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
    joinEstimatesAvailable = context.estimatesAvailable();
    if (joinEstimatesAvailable) {
      for (int role = 0; role <= joinStageCount; role++) {
        joinStatisticsEpochs[role] = context.statistics(role).epoch();
        joinStatisticsRows[role] = context.statistics(role).rowCount();
        joinStatisticsSampled[role] = context.statistics(role).sampled();
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
      joinEstimatedRows[stage] = context.estimatedRows(stage);
    }
  }

  int joinRootAccessColumn(int block) {
    return block == joinBlock ? joinRootAccessColumn : -1;
  }
  int joinStageCount(int block) { return block == joinBlock ? joinStageCount : 0; }
  int joinRightColumn(int block, int stage) {
    return block == joinBlock ? joinRightColumns[stage] : -1;
  }
  int joinAccessKind(int block, int stage) {
    return block == joinBlock ? joinAccessKinds[stage] : 0;
  }
  int joinStrategy(int block, int stage) {
    return block == joinBlock
        ? Byte.toUnsignedInt(joinStrategies[stage]) : SqlJoinStrategy.NESTED_LOOP;
  }
  boolean joinEstimatesAvailable(int block) {
    return block == joinBlock && joinEstimatesAvailable;
  }
  long joinStatisticsEpoch(int block, int role) {
    return block == joinBlock ? joinStatisticsEpochs[role] : 0;
  }
  long joinStatisticsRows(int block, int role) {
    return block == joinBlock ? joinStatisticsRows[role] : 0;
  }
  boolean joinStatisticsSampled(int block, int role) {
    return block == joinBlock && joinStatisticsSampled[role];
  }
  long joinEstimatedRows(int block, int stage) {
    return block == joinBlock ? joinEstimatedRows[stage] : 0;
  }
}
