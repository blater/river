package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;

/** Reusable typed equality contract for values ordered by the shared row store. */
final class SqlDistinctValueKey {
  private final SqlBlockRowValueComparator comparator = new SqlBlockRowValueComparator();
  private int descriptor;

  void begin(int valueDescriptor) { descriptor = valueDescriptor; }

  boolean isText() {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  boolean same(SqlBlockRow left, SqlBlockRow right) {
    return comparator.compare(left, 0, descriptor, right, 0, descriptor) == 0;
  }
}
