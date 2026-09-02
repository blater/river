package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.relational.RelationalLockedCandidateResult;
import io.riverdb.engine.schema.TableDescriptor;

/** Retained descriptor-row materialization for one bounded ordered scan. */
final class SqlDescriptorOrderedRows {
  private final SqlBlockSchema schema;
  private final SqlDescriptorBlockRowValues input;
  private final SqlBlockRow output;
  private final SqlBlockRowStore store;
  private final SqlValueBuffer current = new SqlValueBuffer();
  private final RelationalLockedCandidateResult lockedCandidate =
      new RelationalLockedCandidateResult();
  private TableDescriptor table;
  private SqlDescriptorSetMaterialization materialization;
  private long next;

  SqlDescriptorOrderedRows(SqlSessionShapeBudget budget) {
    schema = new SqlBlockSchema(budget);
    input = new SqlDescriptorBlockRowValues(budget);
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
    if (status.isOk()) status = input.prepare(descriptor, true);
    if (status.isOk()) status = reserveCurrent(descriptor);
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
    if (status.isOk()) status = input.prepare(descriptor, true);
    if (status.isOk()) status = reserveCurrent(descriptor);
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
    status = prepareInput(descriptor);
    if (status.isOk()) status = output.reset(setMaterialization.laneCount());
    if (status.isOk()) status = store.begin(
        setMaterialization.schema(), orderColumns, descending, count);
    if (!status.isOk()) close();
    return status;
  }

  StatusCode append(SqlValueBuffer values, long logicalRowId) {
    StatusCode status = input.load(values, logicalRowId);
    if (!status.isOk()) return status;
    if (materialization == null) return store.append(input.row());
    status = materialization.project(input.row(), output);
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
  SqlValueBuffer currentValues() { return current; }
  boolean candidateLocked() { return lockedCandidate.isLocked(); }

  StatusCode lockCurrent(io.riverdb.engine.relational.RelationalSession session) {
    long rowId = logicalRowId();
    current.reset();
    StatusCode status = session.descriptorRows().lockLogicalCandidate(
        table, rowId, current, lockedCandidate);
    if (status.isOk() && !lockedCandidate.isLocked()) return status;
    if (status.isOk()) status = input.load(current, rowId);
    if (status.isOk()) status = materialization == null
        ? output.copyFrom(input.row()) : materialization.project(input.row(), output);
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
      input.reset();
      current.reset();
      next = 0;
    }
    return status;
  }

  private StatusCode prepareInput(TableDescriptor descriptor) {
    schema.set(descriptor.columnCount());
    StatusCode status = schema.status();
    if (status.isOk()) status = input.prepare(descriptor);
    return status.isOk() ? reserveCurrent(descriptor) : status;
  }

  private StatusCode reserveCurrent(TableDescriptor descriptor) {
    return current.reserve(
        descriptor.columnCount(), descriptor.columnCount(),
        descriptor.encodedMaximumRowBytes(), descriptor.encodedMaximumRowBytes());
  }
}
