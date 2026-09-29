package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Borrows a scan row or copies its typed values before the source is released. */
final class SqlBlockPhysicalRowReader {
  private final SqlPhysicalRowView view;
  private final SqlBlockRow scratch;

  SqlBlockPhysicalRowReader() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlBlockPhysicalRowReader(SqlRetainedArrayAllocator allocator) {
    view = new SqlPhysicalRowView(allocator);
    scratch = new SqlBlockRow(allocator);
  }

  StatusCode prepare(TableDefinition table, SqlBlockRow destination) {
    return prepare(table, destination, null);
  }

  StatusCode prepare(
      TableDefinition table, SqlBlockRow destination, SqlBoundBlockPlans plans) {
    if (table == null || destination == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = destination.reset(table.columnCount());
    if (status.isOk()) status = scratch.reset(table.columnCount());
    return status.isOk() ? view.prepare(table, plans) : status;
  }

  StatusCode borrow(
      long primaryKey, HeapRowResult source, TableDefinition table,
      SqlBlockRow destination) {
    StatusCode status = view.bind(primaryKey, source, table);
    if (!status.isOk()) {
      destination.reset(0);
      return status;
    }
    status = destination.borrow(view);
    if (!status.isOk()) destination.reset(0);
    if (status.isOk()) destination.setKey(primaryKey);
    return status;
  }

  StatusCode read(
      long primaryKey,
      HeapRowResult source,
      TableDefinition table,
      SqlBlockRow destination) {
    StatusCode status = borrow(primaryKey, source, table, scratch);
    return status.isOk() ? destination.copyFrom(scratch) : status;
  }

  void reset() { view.reset(); }
}
