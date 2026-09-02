package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Validates name input before the packed immutable name table is published. */
final class ColumnNameTableFactory {
  private ColumnNameTableFactory() {
  }

  static StatusCode create(
      CharSequence[] names,
      int offset,
      int count,
      int maximumBytes,
      ColumnNameTable.Result result,
      StatusDetail detail) {
    if (result != null) result.reset();
    if (detail != null) detail.reset();
    if (result == null || names == null || offset < 0 || count < 0
        || offset > names.length - count || maximumBytes < 0) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid name arrays");
    }
    return ColumnNameTableBuilder.build(names, offset, count, maximumBytes, result, detail);
  }

  private static StatusCode fail(StatusDetail detail, StatusCode status, CharSequence message) {
    if (detail != null) detail.set(status).append(message);
    return status;
  }
}
