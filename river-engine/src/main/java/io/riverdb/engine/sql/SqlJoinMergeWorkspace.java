package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Ordered outer probes over one indexed or sort-fed merge right input. */
final class SqlJoinMergeWorkspace {
  private final SqlJoinMergeRightRows rightRows;
  private final SqlBlockRow outer;
  private final SqlBlockPhysicalRowReader reader;
  private final SqlBlockRowValueComparator comparator = new SqlBlockRowValueComparator();
  private final SqlJoinMergeKey lastOuter;
  private TableDefinition outerTable;
  private int stage = -1;
  private int outerRole = -1;
  private int outerColumn = -1;
  private int outerDescriptor;
  private boolean active;

  SqlJoinMergeWorkspace(
      RelationalSession relationalSession, SqlSessionShapeBudget budget) {
    this(relationalSession, SqlRetainedArrayAllocator.STANDARD, budget);
  }

  SqlJoinMergeWorkspace(
      RelationalSession relationalSession, SqlRetainedArrayAllocator allocator,
      SqlSessionShapeBudget budget) {
    rightRows = new SqlJoinMergeRightRows(relationalSession, allocator, budget);
    outer = new SqlBlockRow(allocator);
    reader = new SqlBlockPhysicalRowReader(allocator);
    lastOuter = new SqlJoinMergeKey(budget);
  }

  StatusCode begin(SqlBoundJoinContext context) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    int selected = context.physicalStrategyStage();
    if (selected < 0 || context.strategy(selected) != SqlJoinStrategy.MERGE) {
      return StatusCode.OK;
    }
    int role = context.strategyOuterRole(selected);
    TableDefinition definition = context.table(role);
    int keyColumn = context.strategyOuterColumn(selected);
    status = reader.prepare(definition, outer);
    if (!status.isOk()) return status;
    stage = selected;
    outerRole = role;
    outerTable = definition;
    outerColumn = keyColumn;
    outerDescriptor = outerTable.typeDescriptor(outerColumn);
    status = lastOuter.prepare(outerDescriptor);
    if (!status.isOk()) return failBegin(status);
    status = rightRows.begin(
        context.table(stage + 1), context.strategyInnerColumn(stage), outerDescriptor);
    active = status.isOk();
    return status.isOk() ? status : failBegin(status);
  }

  StatusCode beginProbe(SqlJoinRoleRows rows) {
    if (!active) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (rows.nullRole(outerRole)) {
      rightRows.emptyProbe();
      return StatusCode.OK;
    }
    HeapRowResult source = rows.row(outerRole);
    if (source == null) return StatusCode.CORRUPTION;
    StatusCode status = reader.read(
        rows.key(outerRole), source, outerTable, outer);
    if (!status.isOk()) return status;
    if (outer.nullValue(outerColumn)) {
      rightRows.emptyProbe();
      return StatusCode.OK;
    }
    if (lastOuter.available()
        && lastOuter.compare(outer, outerColumn, outerDescriptor, comparator) > 0) {
      return StatusCode.INVARIANT_BROKEN;
    }
    status = lastOuter.capture(outer, outerColumn);
    return status.isOk()
        ? rightRows.beginProbe(outer, outerColumn, outerDescriptor) : status;
  }

  StatusCode nextCandidate() { return rightRows.next(); }
  long key() { return rightRows.key(); }
  HeapRowResult row() { return rightRows.row(); }
  boolean hasResources() { return rightRows.hasResources(); }

  StatusCode close() {
    StatusCode status = rightRows.close();
    if (!status.isOk()) return status;
    reader.reset();
    outer.reset(0);
    outerTable = null;
    stage = -1;
    outerRole = -1;
    outerColumn = -1;
    outerDescriptor = 0;
    lastOuter.reset();
    active = false;
    return StatusCode.OK;
  }

  private StatusCode failBegin(StatusCode failure) {
    StatusCode closed = close();
    return failure.isOk() ? closed : failure;
  }
}
