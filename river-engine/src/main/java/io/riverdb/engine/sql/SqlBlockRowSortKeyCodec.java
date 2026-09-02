package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Reusable canonical key encoder for paged block ordering. */
final class SqlBlockRowSortKeyCodec {
  private final TextView text = new TextView();
  private final SqlBlockKeyBuffer bytes;
  private final SqlBlockSortShape shape;
  private final int[] singleColumn = new int[1];
  private final boolean[] singleDirection = new boolean[1];

  SqlBlockRowSortKeyCodec(SqlSessionShapeBudget budget) {
    shape = new SqlBlockSortShape(
        budget == null ? new SqlSessionShapeBudget(null) : budget);
    bytes = new SqlBlockKeyBuffer(SqlBlockKeyBufferAllocator.DIRECT, budget);
  }

  void beginUnordered() {
    shape.clear();
    bytes.clear();
  }

  boolean beginSingle(SqlBlockSchema schema, int column, boolean descending) {
    if (column < 0) {
      beginUnordered();
      return true;
    }
    singleColumn[0] = column;
    singleDirection[0] = descending;
    return shape.set(schema, singleColumn, singleDirection, 1);
  }

  boolean beginTuple(
      SqlBlockSchema schema, int[] columns, boolean[] descending, int count) {
    return count > 0 && shape.set(schema, columns, descending, count);
  }

  StatusCode encode(SqlBlockRow row) {
    bytes.clear();
    int required = requiredBytes(row);
    if (required < 0 || !bytes.ensure(required, SqlBlockRowRecordCodec.MAXIMUM_RECORD_BYTES)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    for (int part = 0; part < shape.count(); part++) encodePart(row, part);
    bytes.bytes().flip();
    return StatusCode.OK;
  }

  boolean sorted() { return shape.count() > 0; }
  int partCount() { return shape.count(); }
  int descriptor(int part) { return shape.descriptor(part); }
  boolean descending(int part) { return shape.descending(part); }
  ByteBuffer bytes() { return bytes.bytes(); }

  void reset() { bytes.clear(); }
  void close() {
    shape.close();
    bytes.close();
  }

  private int requiredBytes(SqlBlockRow row) {
    long required = 0;
    for (int part = 0; part < shape.count(); part++) {
      int column = shape.column(part);
      required++;
      if (row.nullValue(column)) continue;
      int descriptor = shape.descriptor(part);
      if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        required += SqlTypeDescriptor.isWideDecimal(descriptor) ? 16 : 8;
        continue;
      }
      text.set(row, column);
      int length = Utf8Text.encodedLength(text, Utf8Text.MAXIMUM_SCALARS);
      text.clear();
      if (length < 0) return -1;
      required += Integer.BYTES + length;
    }
    return required > Integer.MAX_VALUE ? -1 : (int) required;
  }

  private void encodePart(SqlBlockRow row, int part) {
    int column = shape.column(part);
    boolean isNull = row.nullValue(column);
    bytes.bytes().put((byte) (isNull ? 0 : 1));
    if (isNull) return;
    int descriptor = shape.descriptor(part);
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      if (SqlTypeDescriptor.isWideDecimal(descriptor)) bytes.bytes().putLong(row.highValue(column));
      bytes.bytes().putLong(row.value(column));
      return;
    }
    text.set(row, column);
    int length = Utf8Text.encodedLength(text, Utf8Text.MAXIMUM_SCALARS);
    bytes.bytes().putInt(length);
    Utf8Text.encode(text, Utf8Text.MAXIMUM_SCALARS, bytes.bytes());
    text.clear();
  }

  private static final class TextView implements CharSequence {
    private SqlBlockRow row;
    private int column;
    void set(SqlBlockRow value, int valueColumn) { row = value; column = valueColumn; }
    void clear() { row = null; column = 0; }
    @Override public int length() { return row.textLength(column); }
    @Override public char charAt(int index) { return row.textCharacter(column, index); }
    @Override public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }
  }
}
