package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;

/** Retained primary-key predicate state and shape validation. */
final class SqlDescriptorPrimaryBinding {
  private final boolean[] assigned = new boolean[SqlShapeLimits.MAX_KEY_PARTS];
  private final SqlDescriptorPrimaryPrograms programs = new SqlDescriptorPrimaryPrograms();
  private final SqlDescriptorPrimaryValues values = new SqlDescriptorPrimaryValues();
  private TableDescriptor table;
  private KeyDescriptor primary;

  SqlValueBuffer values() { return values.buffer(); }

  StatusCode bind(SqlCommand command, TableDescriptor descriptor) {
    reset();
    table = descriptor;
    primary = table == null ? null : table.primaryKey();
    if (command == null || primary == null) return StatusCode.CONFLICT;
    int textBytes = primaryTextBytes();
    if (textBytes < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = values.begin(table.columnCount(), textBytes, command);
    SqlBooleanPredicateProgram where = command.wherePredicates();
    if (status.isOk() && (!where.isAvailable()
        || where.leafCount() != primary.partCount())) status = StatusCode.CONFLICT;
    if (status.isOk()) status = programs.bind(where, command, table, primary, assigned, values);
    if (status.isOk()) status = fillNonKeyNulls();
    return status;
  }

  private StatusCode fillNonKeyNulls() {
    StatusCode status = StatusCode.OK;
    for (int column = 0; status.isOk() && column < table.columnCount(); column++) {
      if (partForColumn(column) < 0) {
        status = values.buffer().setNull(column, table.typeDescriptorAt(column));
      }
    }
    return status;
  }

  private int partForColumn(int column) {
    for (int part = 0; part < primary.partCount(); part++) {
      if (primary.columnOrdinalAt(part) == column) return part;
    }
    return -1;
  }

  private int primaryTextBytes() {
    long bytes = 0;
    for (int part = 0; part < primary.partCount(); part++) {
      int descriptor = primary.typeDescriptorAt(part);
      if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        bytes += SqlTypeDescriptor.parameterOne(descriptor) * 4L;
      }
    }
    return bytes <= SqlShapeLimits.MAX_INDEX_USER_KEY_BYTES ? (int) bytes : -1;
  }

  private void reset() {
    for (int index = 0; index < assigned.length; index++) assigned[index] = false;
    values.reset();
    table = null;
    primary = null;
  }
}
