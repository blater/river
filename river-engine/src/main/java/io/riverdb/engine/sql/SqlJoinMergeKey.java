package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Schema-sized retained key for merge probe monotonicity and duplicate-run reuse. */
final class SqlJoinMergeKey {
  private final SqlBlockRow row;
  private int descriptor;
  private boolean available;

  SqlJoinMergeKey(SqlSessionShapeBudget budget) { row = new SqlBlockRow(budget); }

  StatusCode prepare(int type) {
    descriptor = type;
    available = false;
    return row.reset(1);
  }

  StatusCode capture(SqlBlockRow source, int column) {
    StatusCode status = row.reset(1);
    if (status.isOk()) {
      row.setDecimal128(0, source.highValue(column), source.value(column));
    }
    if (status.isOk()
        && SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      status = source.copyTextTo(column, row, 0);
    }
    if (status.isOk()) available = true;
    return status;
  }

  int compare(
      SqlBlockRow source, int column, int sourceDescriptor,
      SqlBlockRowValueComparator comparator) {
    return comparator.compare(row, 0, descriptor, source, column, sourceDescriptor);
  }

  boolean available() { return available; }
  void invalidate() { available = false; }

  void reset() {
    row.reset(0);
    descriptor = 0;
    available = false;
  }
}
