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
    if (query == null || requirePipeline && !query.isBlockPipeline()
        || query.blockCount() < 2 || query.blockCount() > commands.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    count = query.blockCount();
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
    }
  }

  int count() { return count; }
  SqlCommand command(int block) { return commands[block]; }
  SqlBlockSchema schema(int block) { return schemas[block]; }
  SqlBlockSchema operandSchema(int block) { return operandSchemas[block]; }
  SqlBlockSchema baseSchema() { return baseSchema; }

  void setJoinAccess(int block, BoundSqlStatement bound) {
    joinBlock = block;
    joinRootAccessColumn = bound.accessPredicate >= 0
        && (bound.predicateColumn == 0 || bound.table.hasIndexOn(bound.predicateColumn))
        ? bound.predicateColumn : -1;
    joinStageCount = bound.command.joinChain().stageCount();
    for (int stage = 0; stage < joinStageCount; stage++) {
      int right = bound.joinAccessInnerColumn(stage);
      joinRightColumns[stage] = right;
      boolean indexed = right >= 0
          && (right == 0 || bound.joinRole(stage + 1).hasIndexOn(right));
      boolean unique = indexed
          && (right == 0 || bound.joinRole(stage + 1).hasUniqueIndexOn(right));
      joinAccessKinds[stage] = (byte) (unique ? 2 : indexed ? 1 : 0);
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
}
