package io.riverdb.protocol;

import io.riverdb.base.type.SqlTypeDescriptor;

/** Typed, allocation-free accessors inherited by decoded protocol responses. */
public interface ProtocolResponseValuesView {
  int flags();
  int columnCount();
  long valueAt(int index);
  boolean isNull(int index);
  int typeDescriptorAt(int index);

  default boolean rowAvailable() {
    return (flags() & ProtocolFrameCodec.FLAG_ROW_AVAILABLE) != 0;
  }

  default boolean transactionActive() {
    return (flags() & ProtocolFrameCodec.FLAG_TRANSACTION_ACTIVE) != 0;
  }

  default boolean queryActive() {
    return (flags() & ProtocolFrameCodec.FLAG_QUERY_ACTIVE) != 0;
  }

  default boolean endOfStream() {
    return (flags() & ProtocolFrameCodec.FLAG_END_OF_STREAM) != 0;
  }

  default short smallintAt(int index) {
    return type(index, SqlTypeDescriptor.TYPE_ID_SMALLINT) ? (short) valueAt(index) : 0;
  }

  default int integerAt(int index) {
    return type(index, SqlTypeDescriptor.TYPE_ID_INTEGER) ? (int) valueAt(index) : 0;
  }

  default long bigintAt(int index) {
    return type(index, SqlTypeDescriptor.TYPE_ID_BIGINT) ? valueAt(index) : 0;
  }

  default long decimalUnscaledAt(int index) {
    return type(index, SqlTypeDescriptor.TYPE_ID_DECIMAL)
            && !SqlTypeDescriptor.isWideDecimal(typeDescriptorAt(index))
        ? valueAt(index) : 0;
  }

  default long decimalUnscaledHighAt(int index) {
    return type(index, SqlTypeDescriptor.TYPE_ID_DECIMAL)
        ? valueAt(index) >> Long.SIZE - 1 : 0;
  }

  default long decimalUnscaledLowAt(int index) {
    return type(index, SqlTypeDescriptor.TYPE_ID_DECIMAL) ? valueAt(index) : 0;
  }

  default float realAt(int index) {
    return type(index, SqlTypeDescriptor.TYPE_ID_REAL)
        ? Float.intBitsToFloat((int) valueAt(index)) : 0.0f;
  }

  default double doubleAt(int index) {
    return type(index, SqlTypeDescriptor.TYPE_ID_DOUBLE)
        ? Double.longBitsToDouble(valueAt(index)) : 0.0d;
  }

  default boolean isVarchar(int index) {
    return index >= 0 && index < columnCount()
        && SqlTypeDescriptor.typeId(typeDescriptorAt(index))
            == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  private boolean type(int index, int expected) {
    return index >= 0 && index < columnCount()
        && SqlTypeDescriptor.typeId(typeDescriptorAt(index)) == expected;
  }
}
