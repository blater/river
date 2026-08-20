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
  private int joinRightColumn = -1;
  private int joinOuterAccessColumn = -1;
  private boolean joinRightIndexed;
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
    joinRightColumn = -1;
    joinOuterAccessColumn = -1;
    joinRightIndexed = false;
  }

  int count() { return count; }
  SqlCommand command(int block) { return commands[block]; }
  SqlBlockSchema schema(int block) { return schemas[block]; }
  SqlBlockSchema operandSchema(int block) { return operandSchemas[block]; }
  SqlBlockSchema baseSchema() { return baseSchema; }

  void setJoinAccess(
      int block,
      int right,
      int outerAccess,
      boolean indexed) {
    joinBlock = block;
    joinRightColumn = right;
    joinOuterAccessColumn = outerAccess;
    joinRightIndexed = indexed;
  }

  int joinRightColumn(int block) { return block == joinBlock ? joinRightColumn : -1; }
  int joinOuterAccessColumn(int block) {
    return block == joinBlock ? joinOuterAccessColumn : -1;
  }
  boolean joinRightIndexed(int block) {
    return block == joinBlock && joinRightIndexed;
  }
}
