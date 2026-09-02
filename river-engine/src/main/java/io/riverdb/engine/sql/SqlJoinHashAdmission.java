package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlJoinChain;

/** Pre-scan decoded-row, schema, writer, and hash-index admission. */
final class SqlJoinHashAdmission {
  private SqlJoinHashAdmission() { }

  static StatusCode begin(
      SqlJoinHashWorkspace workspace,
      io.riverdb.sql.SqlCommand command,
      SqlBoundJoinContext context) {
    StatusCode status = workspace.close();
    if (!status.isOk()) return status;
    int selected = selectedStage(command, context);
    if (selected < 0) return StatusCode.OK;
    TableDefinition inner = context.table(selected + 1);
    status = prepareRows(inner,
        context.table(context.strategyOuterRole(selected)), workspace.schema,
        workspace.reader, workspace.buildRow, workspace.candidateRow,
        workspace.outerRow, workspace.writer);
    if (status.isOk()) status = prepareIndex(
        workspace, workspace.allocator,
        SqlJoinHashWorkspace.BUCKETS, SqlJoinHashWorkspace.HASH_ROWS);
    if (!status.isOk()) return status;
    workspace.stage = selected;
    workspace.table = inner;
    workspace.innerColumn = context.strategyInnerColumn(selected);
    workspace.outerDescriptor = context.table(context.strategyOuterRole(selected))
        .typeDescriptor(context.strategyOuterColumn(selected));
    status = workspace.store.begin(workspace.schema, -1, false);
    if (status.isOk()) status = workspace.openBuildScan();
    if (status.isOk()) status = workspace.build();
    if (status.isOk()) status = workspace.store.finish();
    if (status.isOk() && workspace.withinHashCapacity()) status = workspace.indexBuild();
    workspace.active = status.isOk();
    return status.isOk() ? status : workspace.failBegin(status);
  }

  static StatusCode prepareRows(
      TableDefinition inner,
      TableDefinition outer,
      SqlBlockSchema schema,
      SqlBlockPhysicalRowReader reader,
      SqlBlockRow build,
      SqlBlockRow candidate,
      SqlBlockRow probe,
      SqlBlockPhysicalRowWriter writer) {
    schema.set(inner.columnCount());
    StatusCode status = schema.status();
    if (status.isOk()) status = reader.prepare(inner, build);
    if (status.isOk()) status = reader.prepare(inner, candidate);
    if (status.isOk()) status = reader.prepare(outer, probe);
    if (status.isOk()) status = writer.prepare();
    if (!status.isOk()) return status;
    for (int column = 0; column < inner.columnCount(); column++) {
      schema.setColumn(column, inner.columnName(column),
          inner.typeDescriptor(column), inner.isNullable(column));
    }
    return schema.status();
  }

  static Index index(SqlRetainedArrayAllocator allocator, int buckets, int rows) {
    int[] heads = allocator.integers(buckets);
    int[] tails = allocator.integers(buckets);
    int[] next = allocator.integers(rows);
    long[] hashes = allocator.longs(rows);
    return new Index(heads, tails, next, hashes);
  }

  static StatusCode prepareIndex(
      SqlJoinHashWorkspace workspace,
      SqlRetainedArrayAllocator allocator,
      int buckets,
      int rows) {
    if (workspace.heads != null) return StatusCode.OK;
    try {
      Index index = index(allocator, buckets, rows);
      workspace.heads = index.heads();
      workspace.tails = index.tails();
      workspace.next = index.next();
      workspace.hashes = index.hashes();
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  static int selectedStage(
      io.riverdb.sql.SqlCommand command, SqlBoundJoinContext context) {
    SqlJoinChain chain = command.joinChain();
    for (int stage = 0; stage < chain.stageCount(); stage++) {
      if (context.strategy(stage) == SqlJoinStrategy.HASH) return stage;
    }
    return -1;
  }

  record Index(int[] heads, int[] tails, int[] next, long[] hashes) { }
}
