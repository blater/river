package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Defines the legacy scalar row-key field without conflating it with logical row identity. */
final class SqlDescriptorPublicRowKey {
  private SqlDescriptorPublicRowKey() { }

  static long from(TableDescriptor table, SqlValueBuffer row) {
    int column = scalarBigintPrimaryColumn(table);
    return column < 0 || row.isNull(column) ? 0 : row.valueAt(column);
  }

  static long from(TableDescriptor table, SqlBlockRow row) {
    int column = scalarBigintPrimaryColumn(table);
    return column < 0 || row.nullValue(column) ? 0 : row.value(column);
  }

  private static int scalarBigintPrimaryColumn(TableDescriptor table) {
    if (table == null) return -1;
    KeyDescriptor primary = table.primaryKey();
    return primary != null && primary.partCount() == 1
        && primary.typeDescriptorAt(0) == SqlTypeDescriptor.BIGINT
            ? primary.columnOrdinalAt(0) : -1;
  }
}
