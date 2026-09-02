package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Borrowed grouped-result tuple used while evaluating one HAVING predicate. */
final class SqlHavingGroup {
  private SqlBlockRow row;
  private int count;
  private long scalarHigh;
  private long scalar;
  private boolean scalarNull;
  private byte[] scalarText;
  private int scalarTextLength;

  void useScalar(long value, boolean nullValue, byte[] text, int textLength) {
    useScalar(value >> 63, value, nullValue, text, textLength);
  }

  void useScalar(
      long high, long value, boolean nullValue, byte[] text, int textLength) {
    row = null;
    count = 1;
    scalarHigh = high;
    scalar = value;
    scalarNull = nullValue;
    scalarText = text;
    scalarTextLength = textLength;
  }

  void useRow(SqlBlockRow values, int keys) {
    row = values;
    count = keys;
  }

  StatusCode publish(int ordinal, int descriptor, SqlPredicateOperand result) {
    if (!valid(ordinal)) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (nullValue(ordinal)) {
      result.setNull(descriptor);
      return StatusCode.OK;
    }
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      result.setValue(highValue(ordinal), value(ordinal), descriptor, false);
      return StatusCode.OK;
    }
    if (row == null) return result.setUtf8(scalarText, 0, scalarTextLength, descriptor);
    result.setTextCharacters(row.text(ordinal), 0, row.textLength(ordinal), descriptor);
    return StatusCode.OK;
  }

  boolean valid(int ordinal) {
    return ordinal >= 0 && ordinal < count
        && (row == null || ordinal < row.count());
  }

  long value(int ordinal) { return row == null ? scalar : row.value(ordinal); }
  long highValue(int ordinal) {
    return row == null ? scalarHigh : row.highValue(ordinal);
  }
  boolean nullValue(int ordinal) {
    return row == null ? scalarNull : row.nullValue(ordinal);
  }

  void clear() {
    row = null;
    count = 0;
    scalar = 0;
    scalarHigh = 0;
    scalarNull = false;
    scalarText = null;
    scalarTextLength = 0;
  }
}
