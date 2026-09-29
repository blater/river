package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDescriptorScanCursor;
import io.riverdb.engine.relational.RelationalRowIdentityResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.relational.StoredTableRowIntegerFilter;
import io.riverdb.engine.relational.StoredTableColumnSelection;
import io.riverdb.engine.relational.StoredTableRowView;

/** Reusable row for one streaming descriptor join role. */
final class SqlUniversalDescriptorJoinRow {
  private final RelationalRowIdentityResult identity = new RelationalRowIdentityResult();
  private final StoredTableRowView values = new StoredTableRowView();
  private final SqlBlockRow row = new SqlBlockRow();
  private final StoredTableColumnSelection selected = new StoredTableColumnSelection();

  StatusCode selectNone(TableDescriptor table) {
    StatusCode status = selected.selectNone(table.columnCount());
    if (!status.isOk()) return status;
    if (table.primaryKey() != null) {
      for (int part = 0; part < table.primaryKey().partCount(); part++) {
        selected.select(table.primaryKey().columnOrdinalAt(part));
      }
    }
    return StatusCode.OK;
  }

  void select(int column) { selected.select(column); }
  void selectAll() { selected.selectAll(); }

  StatusCode prepare(TableDescriptor table) {
    StatusCode status = selected.selectNone(table.columnCount());
    if (status.isOk()) selected.selectAll();
    if (status.isOk()) status = row.reset(table.columnCount());
    return status;
  }

  StatusCode next(
      RelationalSession session, RelationalDescriptorScanCursor cursor,
      TableDescriptor table) {
    return next(session, cursor, table, null);
  }

  StatusCode next(
      RelationalSession session, RelationalDescriptorScanCursor cursor,
      TableDescriptor table, StoredTableRowIntegerFilter filter) {
    StatusCode status = session.descriptorRows().nextScan(
        cursor, values, identity, filter, selected);
    if (status.isOk()) status = row.borrow(values, selected);
    if (status.isOk()) row.setKey(SqlDescriptorPublicRowKey.from(table, row));
    return status;
  }

  long key() { return identity.logicalRowId(); }
  long publicKey() { return row.key(); }
  SqlBlockRow row() { return row; }
  void reset() {
    row.reset(0);
    values.reset();
    identity.reset();
    selected.selectAll();
  }
}
