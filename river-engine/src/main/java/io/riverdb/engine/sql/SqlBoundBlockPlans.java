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
  private final SqlBlockSchema baseSchema;
  private final SqlBlockProjectionLiveness liveness = new SqlBlockProjectionLiveness();
  private final SqlUniversalDescriptorIndexAccess rootAccess =
      new SqlUniversalDescriptorIndexAccess();
  private final SqlBoundJoinSnapshot joinSnapshot = new SqlBoundJoinSnapshot();
  private int rootAccessColumn = -1;
  private boolean descriptorSource;
  private int count;

  SqlBoundBlockPlans(SqlSessionShapeBudget budget) {
    baseSchema = new SqlBlockSchema(budget);
    for (int index = 0; index < schemas.length; index++) {
      schemas[index] = new SqlBlockSchema(budget);
      operandSchemas[index] = new SqlBlockSchema(budget);
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
        || sourceBlocks < 1 || sourceBlocks == 1
            && !query.isBlockPipeline() && !query.hasNestedTopology()
        || sourceBlocks > commands.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    count = sourceBlocks;
    for (int index = 0; index < count; index++) {
      commands[index] = query.block(index);
    }
    return StatusCode.OK;
  }

  void reset() {
    liveness.reset(count);
    for (int index = 0; index < count; index++) {
      commands[index] = null;
      schemas[index].reset();
      operandSchemas[index].reset();
    }
    count = 0;
    baseSchema.reset();
    joinSnapshot.reset();
    rootAccessColumn = -1;
    rootAccess.reset();
    descriptorSource = false;
  }

  void setDescriptorSource(boolean descriptor) { descriptorSource = descriptor; }
  boolean descriptorSource() { return descriptorSource; }
  void setRootAccessColumn(int column) { rootAccessColumn = column; }
  int rootAccessColumn() { return rootAccessColumn; }
  SqlUniversalDescriptorIndexAccess rootAccess() { return rootAccess; }
  SqlBoundJoinSnapshot joinSnapshot() { return joinSnapshot; }

  int count() { return count; }
  SqlCommand command(int block) { return commands[block]; }
  SqlBlockSchema schema(int block) { return schemas[block]; }
  SqlBlockSchema operandSchema(int block) { return operandSchemas[block]; }
  SqlBlockSchema baseSchema() { return baseSchema; }

  void prepareProjectionLiveness() {
    liveness.prepare(commands, schemas, count);
  }

  boolean projectionLive(int block, int projection) {
    return liveness.live(block, projection, schemas, count);
  }

}
