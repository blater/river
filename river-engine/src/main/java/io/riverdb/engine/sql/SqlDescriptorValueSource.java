package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;

/** Allocation-free value view over a decoded or materialized descriptor row. */
final class SqlDescriptorValueSource {
  private SqlValueBuffer decoded;
  private SqlBlockRow materialized;

  SqlDescriptorValueSource use(SqlValueBuffer values) {
    decoded = values;
    materialized = null;
    return this;
  }

  SqlDescriptorValueSource use(SqlBlockRow values) {
    decoded = null;
    materialized = values;
    return this;
  }

  boolean isNull(int column) {
    return decoded != null ? decoded.isNull(column) : materialized.nullValue(column);
  }

  long value(int column) {
    return decoded != null ? decoded.valueAt(column) : materialized.value(column);
  }

  long highValue(int column) {
    return decoded != null
        ? decoded.highValueAt(column) : materialized.highValue(column);
  }

  StatusCode text(int column, int descriptor, SqlPredicateOperand result) {
    return decoded != null
        ? result.setText(decoded, column, descriptor)
        : result.setText(materialized, column, descriptor);
  }
}
