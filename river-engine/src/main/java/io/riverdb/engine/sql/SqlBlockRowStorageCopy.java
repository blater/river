package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Deep copy between primitive block-row stores. */
final class SqlBlockRowStorageCopy {
  private SqlBlockRowStorageCopy() { }

  static StatusCode copy(SqlBlockRowStorage source, SqlBlockRowStorage target) {
    StatusCode status = target.begin(source.count());
    if (!status.isOk()) return status;
    for (int column = 0; column < source.count(); column++) {
      if (source.isNull(column)) target.setNull(column);
      else target.value(column, source.highValue(column), source.value(column));
      int length = source.textLength(column);
      if (length > 0) {
        char[] text = source.existingText(column);
        if (text == null) return source.status().isOk()
            ? StatusCode.CORRUPTION : source.status();
        status = target.prepareText(column, length);
        if (!status.isOk()) return status;
        System.arraycopy(text, 0, target.existingText(column), 0, length);
      }
      target.textLength(column, length);
    }
    return target.status();
  }
}
