package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlQuery;

/** Optional bounded high words for cached two-lane decimal values. */
final class SqlSubqueryHighValueLane {
  private long[] values;

  void prepare(int descriptor, int capacity) {
    if (SqlTypeDescriptor.isWideDecimal(descriptor) && values == null) {
      values = new long[capacity];
    }
  }

  void prepareScalar(int kind, int descriptor, int capacity) {
    if (kind == SqlQuery.SUBQUERY_SCALAR) prepare(descriptor, capacity);
  }

  void set(int index, long value, int descriptor, int capacity) {
    prepare(descriptor, capacity);
    if (values != null) values[index] = value;
  }

  long value(int index, long low) {
    return values == null ? low >> 63 : values[index];
  }

  void clear(int index) {
    if (values != null) values[index] = 0;
  }
}
