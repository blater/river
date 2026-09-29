package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalLockedCandidateResult;
import io.riverdb.engine.relational.SqlValueAccess;
import io.riverdb.engine.relational.StoredTableRowView;
import io.riverdb.engine.schema.TableDescriptor;

/** Retained descriptor-row materialization for one bounded ordered scan. */
final class SqlDescriptorOrderedRows {
  private final SqlBlockSchema schema;
  private final SqlBlockRow input;
  private final SqlBlockRow output;
  private final SqlBlockRowStore store;
  private final StoredTableRowView current = new StoredTableRowView();
  private final RelationalLockedCandidateResult lockedCandidate =
      new RelationalLockedCandidateResult();
  private TableDescriptor table;
  private SqlDescriptorSetMaterialization materialization;
  private long next;

  SqlDescriptorOrderedRows(SqlSessionShapeBudget budget) {
    schema = new SqlBlockSchema(budget);
    input = new SqlBlockRow(budget);
    output = new SqlBlockRow(budget);
    store = new SqlBlockRowStore(budget);
  }

  StatusCode begin(
      TableDescriptor descriptor, int orderColumn, boolean descending) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    table = descriptor;
    schema.set(descriptor.columnCount() + 1);
    for (int column = 0; column < descriptor.columnCount(); column++) {
      schema.setColumn(
          column, "", descriptor.typeDescriptorAt(column), descriptor.isNullable(column));
    }
    schema.setColumn(descriptor.columnCount(), "", io.riverdb.base.type.SqlTypeDescriptor.BIGINT,
        false);
    status = schema.status();
    if (status.isOk()) status = input.reset(descriptor.columnCount() + 1);
    if (status.isOk()) status = output.reset(descriptor.columnCount() + 1);
    if (status.isOk()) status = store.begin(schema, orderColumn, descending);
    if (!status.isOk()) close();
    return status;
  }

  StatusCode begin(
      TableDescriptor descriptor, int[] orderColumns, boolean[] descending, int count) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    table = descriptor;
    schema.set(descriptor.columnCount() + 1);
    for (int column = 0; column < descriptor.columnCount(); column++) {
      schema.setColumn(
          column, "", descriptor.typeDescriptorAt(column), descriptor.isNullable(column));
    }
    schema.setColumn(descriptor.columnCount(), "", io.riverdb.base.type.SqlTypeDescriptor.BIGINT,
        false);
    status = schema.status();
    if (status.isOk()) status = input.reset(descriptor.columnCount() + 1);
    if (status.isOk()) status = output.reset(descriptor.columnCount() + 1);
    if (status.isOk()) status = store.begin(schema, orderColumns, descending, count);
    if (!status.isOk()) close();
    return status;
  }

  StatusCode begin(
      TableDescriptor descriptor,
      SqlDescriptorSetMaterialization setMaterialization,
      int[] orderColumns,
      boolean[] descending,
      int count) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    table = descriptor;
    materialization = setMaterialization;
    status = output.reset(setMaterialization.laneCount());
    if (status.isOk()) status = store.begin(
        setMaterialization.schema(), orderColumns, descending, count);
    if (!status.isOk()) close();
    return status;
  }

  StatusCode append(SqlValueAccess values, long logicalRowId) {
    if (materialization == null) {
      StatusCode status = input.borrow(values, logicalRowId);
      return status.isOk() ? store.append(input) : status;
    }
    StatusCode status = materialization.project(values, output);
    return status.isOk() ? store.append(output) : status;
  }

  StatusCode finish() {
    StatusCode status = store.finish();
    if (status.isOk()) next = 0;
    return status;
  }

  StatusCode read() {
    return next >= store.rowCount()
        ? StatusCode.CONFLICT : store.readAt(next, output);
  }

  StatusCode readOffset(long offset) {
    if (offset < 0 || next > Long.MAX_VALUE - offset) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long position = next + offset;
    return position >= store.rowCount()
        ? StatusCode.CONFLICT : store.readAt(position, output);
  }

  SqlBlockRow row() { return output; }
  SqlValueAccess currentValues() { return current; }
  boolean candidateLocked() { return lockedCandidate.isLocked(); }

  StatusCode lockCurrent(io.riverdb.engine.relational.RelationalSession session) {
    long rowId = logicalRowId();
    current.reset();
    StatusCode status = session.descriptorRows().lockLogicalCandidate(
        table, rowId, current, lockedCandidate);
    if (status.isOk() && !lockedCandidate.isLocked()) return status;
    if (status.isOk() && materialization == null) status = input.borrow(current, rowId);
    if (status.isOk()) status = materialization == null
        ? output.copyFrom(input) : materialization.project(current, output);
    if (!status.isOk() && session.descriptorRows().currentBorrowed()) {
      StatusCode release = session.descriptorRows().releaseCurrent();
      if (!release.isOk()) status = release;
    }
    return status;
  }
  TableDescriptor table() { return table; }
  long logicalRowId() { return output.value(table.columnCount()); }
  void advance() { next++; }
  StatusCode advance(long count) {
    if (count < 0 || next > Long.MAX_VALUE - count) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    next += count;
    return StatusCode.OK;
  }

  boolean active() { return table != null; }

  StatusCode close() {
    StatusCode status = store.close();
    if (status.isOk()) {
      schema.reset();
      table = null;
      materialization = null;
      input.reset(0);
      current.reset();
      next = 0;
    }
    return status;
  }

}
