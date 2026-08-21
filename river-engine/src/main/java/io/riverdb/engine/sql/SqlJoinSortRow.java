package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Reusable canonical UTF-8 image for one projected JOIN tuple in the sorter. */
final class SqlJoinSortRow {
  private final Text text = new Text();
  private ByteBuffer bytes;
  private final HeapRowResult row = new HeapRowResult();

  StatusCode encode(
      SqlBlockRow source, int[] descriptors, int columns) {
    if (bytes == null) return StatusCode.CONFLICT;
    bytes.clear();
    bytes.position(columns * Long.BYTES);
    for (int column = 0; column < columns; column++) {
      if (SqlTypeDescriptor.typeId(descriptors[column])
              != SqlTypeDescriptor.TYPE_ID_VARCHAR
          || source.nullValue(column)) {
        bytes.putLong(column * Long.BYTES, source.value(column));
        continue;
      }
      int offset = bytes.position();
      text.set(source, column);
      int encoded = Utf8Text.encode(text, Utf8Text.MAXIMUM_SCALARS, bytes);
      if (encoded < 0) {
        text.clear();
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      bytes.putLong(column * Long.BYTES, (long) offset << 32 | encoded);
    }
    text.clear();
    int length = bytes.position();
    bytes.flip();
    row.set(bytes, 0, 0, length);
    return StatusCode.OK;
  }

  HeapRowResult row() { return row; }

  void prepare() {
    if (bytes == null) {
      bytes = ByteBuffer.allocateDirect(TableSchema.MAXIMUM_ROW_BYTES);
    }
  }

  private static final class Text implements CharSequence {
    private SqlBlockRow row;
    private int column;
    void set(SqlBlockRow source, int sourceColumn) {
      row = source;
      column = sourceColumn;
    }
    void clear() { row = null; column = 0; }
    @Override public int length() { return row.textLength(column); }
    @Override public char charAt(int index) { return row.textCharacter(column, index); }
    @Override public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }
  }
}
