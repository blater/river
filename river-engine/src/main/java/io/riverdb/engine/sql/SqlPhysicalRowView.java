package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.BoundedByteSource;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.SqlValueAccess;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Borrowed physical values, valid until the owning scan result is reused or released. */
final class SqlPhysicalRowView implements SqlValueAccess {
  private final SqlRetainedArrayAllocator allocator;
  private int[] liveText = new int[0];
  private boolean[] requiredText = new boolean[0];
  private int liveTextCount;
  private boolean selectedText;
  private TableDefinition table;
  private HeapRowResult row;
  private long key;

  SqlPhysicalRowView(SqlRetainedArrayAllocator retainedAllocator) {
    allocator = retainedAllocator;
  }

  StatusCode prepare(TableDefinition definition, SqlBoundBlockPlans plans) {
    reset();
    if (definition == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    int columns = definition.columnCount();
    if (plans != null && columns > liveText.length) {
      try {
        int[] nextLive = allocator.integers(columns);
        boolean[] nextRequired = allocator.booleans(columns);
        liveText = nextLive;
        requiredText = nextRequired;
      } catch (OutOfMemoryError error) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    selectedText = plans != null;
    if (selectedText) {
      for (int column = 1; column < columns; column++) {
        if (!definition.isVarchar(column)) continue;
        boolean required = plans.physicalColumnLive(column);
        requiredText[column] = required;
        if (required) liveText[liveTextCount++] = column;
      }
    }
    table = definition;
    return StatusCode.OK;
  }

  StatusCode bind(long primaryKey, HeapRowResult source, TableDefinition definition) {
    row = null;
    key = 0;
    if (table == null || definition == null || selectedText && table != definition
        || source == null
        || source.length() < definition.fixedRowBytes()
        || source.length() > definition.maximumRowBytes()) return StatusCode.CORRUPTION;
    int count = selectedText ? liveTextCount : definition.columnCount() - 1;
    for (int index = 0; index < count; index++) {
      int column = selectedText ? liveText[index] : index + 1;
      if (!definition.isVarchar(column)
          || SqlPhysicalRowNulls.get(source, definition, column)) continue;
      long handle = source.getLong(definition.valueOffset(column));
      int offset = (int) (handle >>> 32);
      int length = (int) handle;
      if (offset < definition.fixedRowBytes() || length < 0
          || offset > source.length() - length) return StatusCode.CORRUPTION;
    }
    table = definition;
    row = source;
    key = primaryKey;
    return StatusCode.OK;
  }

  void reset() {
    row = null;
    table = null;
    key = 0;
    liveTextCount = 0;
    selectedText = false;
  }

  @Override public int count() { return table == null ? 0 : table.columnCount(); }
  @Override public int descriptorAt(int column) { return table.typeDescriptor(column); }
  @Override public boolean isNull(int column) {
    return column > 0 && SqlPhysicalRowNulls.get(row, table, column);
  }
  @Override public long valueAt(int column) {
    if (column == 0) return key;
    if (isNull(column) || table.isVarchar(column)) return 0;
    return row.getLong(table.valueOffset(column));
  }
  @Override public long highValueAt(int column) {
    if (column == 0) return key >> 63;
    if (isNull(column) || table.isVarchar(column)) return 0;
    return SqlTypeDescriptor.isWideDecimal(table.typeDescriptor(column))
        ? row.getLong(table.highValueOffset(column)) : valueAt(column) >> 63;
  }
  @Override public int textByteLengthAt(int column) {
    if (column <= 0 || !table.isVarchar(column) || isNull(column)) return -1;
    return !selectedText || requiredText[column]
        ? (int) row.getLong(table.valueOffset(column)) : 0;
  }
  @Override public int textByteOffsetAt(int column) {
    if (column <= 0 || !table.isVarchar(column) || isNull(column)) return -1;
    return !selectedText || requiredText[column]
        ? (int) (row.getLong(table.valueOffset(column)) >>> 32) : 0;
  }
  @Override public BoundedByteSource textSource(int column) { return row; }
  @Override public int copyTextChars(
      int column, char[] destination, int destinationOffset) {
    int length = textByteLengthAt(column);
    if (length < 0 || destinationOffset < 0 || destinationOffset > destination.length) {
      return -1;
    }
    if (length == 0) return 0;
    return Utf8RowText.decode(
        row, textByteOffsetAt(column), length, destination, destinationOffset);
  }
}
