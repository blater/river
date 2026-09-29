package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Rehomes one decoded block row into a reusable canonical physical row image. */
final class SqlBlockPhysicalRowWriter {
  private final SqlRetainedArrayAllocator allocator;
  private final TextView text = new TextView();
  private final HeapRowResult row = new HeapRowResult();
  private ByteBuffer bytes;

  SqlBlockPhysicalRowWriter() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlBlockPhysicalRowWriter(SqlRetainedArrayAllocator retainedAllocator) {
    allocator = retainedAllocator;
  }

  StatusCode prepare() {
    if (bytes != null) return StatusCode.OK;
    try {
      ByteBuffer next = allocator.direct(TableSchema.MAXIMUM_ROW_BYTES);
      bytes = next;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode write(SqlBlockRow source, TableDefinition table) {
    if (bytes == null || source == null || table == null
        || source.count() != table.columnCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    bytes.clear();
    int payload = table.fixedRowBytes();
    SqlPhysicalRowNulls.clear(bytes, table);
    for (int column = 1; column < table.columnCount(); column++) {
      int slot = table.valueOffset(column);
      if (source.nullValue(column)) {
        if (!table.isNullable(column)) return StatusCode.CORRUPTION;
        SqlPhysicalRowNulls.set(bytes, table, column, true);
        bytes.putLong(slot, 0);
        if (io.riverdb.base.type.SqlTypeDescriptor.isWideDecimal(
            table.typeDescriptor(column))) {
          bytes.putLong(table.highValueOffset(column), 0);
        }
      } else if (table.isVarchar(column)) {
        int maximum = io.riverdb.base.type.SqlTypeDescriptor.parameterOne(
            table.typeDescriptor(column));
        int length;
        if (source.hasUtf8(column)) {
          length = source.utf8Length(column);
          if (length < 0 || length > bytes.capacity() - payload
              || Utf8Text.trustedScalarCount(
                  source.utf8Slice(column), 0, length) > maximum) {
            return StatusCode.CORRUPTION;
          }
          for (int index = 0; index < length; index++) {
            bytes.put(payload + index, source.utf8ByteAt(column, index));
          }
        } else {
          text.set(source, column);
          bytes.position(payload);
          length = Utf8Text.encode(text, maximum, bytes);
          text.clear();
        }
        if (length < 0) return StatusCode.CORRUPTION;
        bytes.putLong(slot, (long) payload << 32 | Integer.toUnsignedLong(length));
        payload += length;
      } else {
        bytes.putLong(slot, source.value(column));
        if (io.riverdb.base.type.SqlTypeDescriptor.isWideDecimal(
            table.typeDescriptor(column))) {
          bytes.putLong(table.highValueOffset(column), source.highValue(column));
        }
      }
    }
    bytes.position(0);
    bytes.limit(payload);
    if (!table.hasSafeStoredRowLayout(bytes)) return StatusCode.CORRUPTION;
    row.set(bytes, 0, 0, payload);
    return StatusCode.OK;
  }

  long key(SqlBlockRow source) { return source.value(0); }
  HeapRowResult row() { return row; }

  void reset() {
    row.reset();
    text.clear();
    if (bytes == null) return;
    bytes.clear();
    for (int index = 0; index < bytes.capacity(); index++) bytes.put(index, (byte) 0);
    bytes.clear();
  }

  private static final class TextView implements CharSequence {
    private SqlBlockRow row;
    private int column;
    void set(SqlBlockRow source, int sourceColumn) { row = source; column = sourceColumn; }
    void clear() { row = null; column = 0; }
    @Override public int length() { return row.textLength(column); }
    @Override public char charAt(int index) { return row.textCharacter(column, index); }
    @Override public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }
  }
}
