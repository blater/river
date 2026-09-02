package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.relational.RelationalDescriptorScanCursor;
import io.riverdb.engine.relational.RelationalRowIdentityResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.schema.TableDescriptor;

/** Reusable decoded row for one streaming descriptor join role. */
final class SqlUniversalDescriptorJoinRow {
  private final RelationalRowIdentityResult identity = new RelationalRowIdentityResult();
  private final SqlValueBuffer values = new SqlValueBuffer();
  private final SqlBlockRow row = new SqlBlockRow();

  StatusCode prepare(TableDescriptor table) {
    StatusCode status = values.reserve(
        table.columnCount(), table.columnCount(),
        SqlShapeLimits.MAX_STORED_ROW_BYTES, SqlShapeLimits.MAX_STORED_ROW_BYTES);
    if (status.isOk()) status = row.reset(table.columnCount());
    return status;
  }

  StatusCode next(
      RelationalSession session, RelationalDescriptorScanCursor cursor,
      TableDescriptor table) {
    StatusCode status = session.descriptorRows().nextScan(cursor, values, identity);
    if (status.isOk()) status = row.reset(table.columnCount());
    for (int column = 0; status.isOk() && column < table.columnCount(); column++) {
      status = copy(table, column);
    }
    if (status.isOk()) row.setKey(SqlDescriptorPublicRowKey.from(table, values));
    return status;
  }

  private StatusCode copy(TableDescriptor table, int column) {
    if (values.isNull(column)) {
      row.setNull(column);
      return StatusCode.OK;
    }
    int type = table.typeDescriptorAt(column);
    if (SqlTypeDescriptor.isWideDecimal(type)) {
      row.setDecimal128(column, values.highValueAt(column), values.valueAt(column));
    } else if (SqlTypeDescriptor.typeId(type) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      row.setValue(column, values.valueAt(column));
    } else {
      int bytes = values.textByteLengthAt(column);
      if (bytes < 0) return StatusCode.CORRUPTION;
      if (bytes == 0) {
        row.setTextLength(column, 0);
        return StatusCode.OK;
      }
      StatusCode status = row.prepareText(column, bytes);
      if (!status.isOk()) return status;
      int length = values.copyTextChars(column, row.text(column), 0);
      if (length < 0) return StatusCode.CORRUPTION;
      row.setTextLength(column, length);
    }
    return StatusCode.OK;
  }

  long key() { return identity.logicalRowId(); }
  long publicKey() { return row.key(); }
  SqlBlockRow row() { return row; }
  void reset() { values.reset(); identity.reset(); }
}
