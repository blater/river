package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Ordered outer probes over one indexed or sort-fed merge right input. */
final class SqlJoinMergeWorkspace {
  private final SqlJoinMergeRightRows rightRows;
  private final SqlBlockRow outer = new SqlBlockRow();
  private final SqlBlockPhysicalRowReader reader = new SqlBlockPhysicalRowReader();
  private TableDefinition outerTable;
  private int stage = -1;
  private int outerRole = -1;
  private int outerColumn = -1;
  private int outerDescriptor;
  private long lastOuterValue;
  private boolean active;
  private boolean lastOuterAvailable;

  SqlJoinMergeWorkspace(RelationalSession relationalSession) {
    rightRows = new SqlJoinMergeRightRows(relationalSession);
  }

  StatusCode begin(BoundSqlStatement bound) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    stage = bound.physicalJoinStrategyStage();
    if (stage < 0 || bound.joinStrategy(stage) != SqlJoinStrategy.MERGE) {
      return StatusCode.OK;
    }
    outerRole = bound.joinStrategyOuterRole(stage);
    outerTable = bound.joinRole(outerRole);
    outerColumn = bound.joinStrategyOuterColumn(stage);
    outerDescriptor = outerTable.typeDescriptor(outerColumn);
    prepareOuter();
    status = rightRows.begin(
        bound.joinRole(stage + 1), bound.joinStrategyInnerColumn(stage));
    active = status.isOk();
    return status;
  }

  StatusCode beginProbe(
      SqlJoinRoleRows rows, SqlExpressionEvaluator expressions) {
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
    long value = outer.value(outerColumn);
    if (lastOuterAvailable
        && expressions.compareExact(
            value, outerDescriptor, lastOuterValue, outerDescriptor) < 0) {
      return StatusCode.INVARIANT_BROKEN;
    }
    lastOuterValue = value;
    lastOuterAvailable = true;
    return rightRows.beginProbe(value, outerDescriptor, expressions);
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
    lastOuterValue = 0;
    active = false;
    lastOuterAvailable = false;
    return StatusCode.OK;
  }

  private void prepareOuter() {
    outer.reset(outerTable.columnCount());
    for (int column = 0; column < outerTable.columnCount(); column++) {
      if (outerTable.isVarchar(column)) outer.prepareText(column);
    }
  }
}
