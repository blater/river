package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.BoundedByteSource;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.engine.relational.SqlValueAccess;
import io.riverdb.engine.relational.StoredTableColumnSelection;
import java.nio.ByteBuffer;

/** Synchronous evaluator scratch; retained boundaries immediately encode canonical UTF-8. */
final class SqlBlockRow implements SqlNullWords {
  private final SqlBlockRowStorage storage;
  private final Utf8Slice utf8Slice = new Utf8Slice();
  private SqlValueAccess borrowed;
  private StoredTableColumnSelection selected;
  private boolean[] decoded = new boolean[0];
  private int[] decodedColumns = new int[0];
  private int decodedCount;
  private boolean appendedLogicalRowId;
  private long logicalRowId;
  private long key;

  SqlBlockRow() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlBlockRow(SqlRetainedArrayAllocator allocator) {
    storage = new SqlBlockRowStorage(allocator);
  }

  SqlBlockRow(SqlSessionShapeBudget budget) {
    storage = new SqlBlockRowStorage(SqlRetainedArrayAllocator.STANDARD, budget);
  }

  StatusCode reset(int columns) {
    clearBorrow();
    key = 0;
    return storage.begin(columns);
  }

  /** Borrows one role-owned view until that role advances or resets. */
  StatusCode borrow(SqlValueAccess source, StoredTableColumnSelection columns) {
    return borrow(source, columns, false, 0);
  }

  StatusCode borrow(SqlValueAccess source) {
    return borrow(source, null, false, 0);
  }

  StatusCode borrow(SqlValueAccess source, long rowId) {
    return borrow(source, null, true, rowId);
  }

  private StatusCode borrow(
      SqlValueAccess source, StoredTableColumnSelection columns,
      boolean appendRowId, long rowId) {
    if (source == null || columns != null && !columns.matches(source.count())) {
      return StatusCode.INVARIANT_BROKEN;
    }
    clearBorrow();
    int count = source.count() + (appendRowId ? 1 : 0);
    if (decoded.length < count) {
      try {
        boolean[] nextDecoded = new boolean[count];
        int[] nextColumns = new int[count];
        decoded = nextDecoded;
        decodedColumns = nextColumns;
      } catch (OutOfMemoryError error) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    borrowed = source;
    selected = columns;
    appendedLogicalRowId = appendRowId;
    logicalRowId = rowId;
    key = 0;
    return StatusCode.OK;
  }

  private void clearBorrow() {
    for (int index = 0; index < decodedCount; index++) {
      int column = decodedColumns[index];
      storage.clearValue(column);
      decoded[column] = false;
    }
    decodedCount = 0;
    borrowed = null;
    selected = null;
    appendedLogicalRowId = false;
    logicalRowId = 0;
  }

  void setValue(int column, long value) { storage.value(column, value); }
  void setDecimal128(int column, long high, long low) {
    storage.value(column, high, low);
  }
  void setNull(int column) { storage.setNull(column); }
  StatusCode setText(int column, char[] source, int offset, int length) {
    if (source == null || offset < 0 || length < 0 || offset > source.length - length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (length == 0) {
      storage.textLength(column, 0);
      return StatusCode.OK;
    }
    StatusCode status = prepareText(column, length);
    if (!status.isOk()) return status;
    char[] target = text(column);
    System.arraycopy(source, offset, target, 0, length);
    storage.textLength(column, length);
    return StatusCode.OK;
  }
  StatusCode setUtf8(int column, BoundedByteSource source, int offset, int length) {
    return storage.setUtf8(column, source, offset, length);
  }
  StatusCode setUtf8(int column, ByteBuffer source, int offset, int length) {
    return storage.setUtf8(column, source, offset, length);
  }
  StatusCode setUtf8(int column, byte[] source, int offset, int length) {
    return storage.setUtf8(column, source, offset, length);
  }
  void setTextLength(int column, int length) { storage.textLength(column, length); }
  void clearValue(int column) { storage.clearValue(column); }
  StatusCode copyFrom(SqlBlockRow source) {
    if (source == this) return StatusCode.OK;
    StatusCode status;
    if (source.borrowed == null) {
      clearBorrow();
      status = storage.copyFrom(source.storage);
    } else status = copyBorrowed(source);
    if (status.isOk()) key = source.key;
    return status;
  }

  private StatusCode copyBorrowed(SqlBlockRow source) {
    StatusCode status = reset(source.count());
    for (int column = 0; status.isOk() && column < source.count(); column++) {
      if (source.nullValue(column)) {
        setNull(column);
        continue;
      }
      setDecimal128(column, source.highValue(column), source.value(column));
      if (!source.appendedColumn(column)
          && source.borrowed.textByteLengthAt(column) >= 0) {
        status = setUtf8(
            column, source.borrowed.textSource(column),
            source.borrowed.textByteOffsetAt(column),
            source.borrowed.textByteLengthAt(column));
      }
    }
    return status.isOk() ? storage.status() : status;
  }
  void setKey(long value) { key = value; }
  long key() { return key; }
  StatusCode status() { return storage.status(); }
  long value(int column) { return borrowed == null ? storage.value(column)
      : appendedColumn(column) ? logicalRowId
      : selected == null || selected.includes(column) ? borrowed.valueAt(column) : 0; }
  long highValue(int column) { return borrowed == null ? storage.highValue(column)
      : appendedColumn(column) ? logicalRowId >> 63
      : selected == null || selected.includes(column) ? borrowed.highValueAt(column) : 0; }
  boolean nullValue(int column) { return borrowed == null ? storage.isNull(column)
      : appendedColumn(column) ? false
      : selected != null && !selected.includes(column) || borrowed.isNull(column); }
  @Override public long nullWord(int word) {
    if (borrowed == null) return storage.nullWord(word);
    long result = 0;
    int start = word << 6;
    for (int bit = 0; bit < Long.SIZE && start + bit < count(); bit++) {
      if (nullValue(start + bit)) result |= 1L << bit;
    }
    return result;
  }
  @Override public int nullWordCount() { return borrowed == null
      ? storage.nullWordCount() : (count() + Long.SIZE - 1) >>> 6; }
  int count() { return borrowed == null ? storage.count()
      : borrowed.count() + (appendedLogicalRowId ? 1 : 0); }
  int textLength(int column) {
    if (borrowed == null) return storage.textLength(column);
    if (appendedColumn(column) || nullValue(column)) return 0;
    int length = borrowed.textByteLengthAt(column);
    return length < 0 ? 0 : Utf8Text.trustedUtf16Length(
        borrowed.textSource(column), borrowed.textByteOffsetAt(column), length);
  }
  StatusCode prepareText(int column) { return storage.prepareText(column); }
  StatusCode prepareText(int column, int characters) {
    return storage.prepareText(column, characters);
  }
  char[] text(int column) {
    if (borrowed == null) return storage.text(column);
    if (appendedColumn(column) || nullValue(column)) return null;
    if (!decoded[column]) {
      int bytes = borrowed.textByteLengthAt(column);
      StatusCode status = storage.prepareText(column, Math.max(1, bytes));
      if (!status.isOk()) return null;
      int length = borrowed.copyTextChars(column, storage.text(column), 0);
      if (length < 0 || length > bytes) return null;
      storage.textLength(column, length);
      decoded[column] = true;
      decodedColumns[decodedCount++] = column;
    }
    return storage.text(column);
  }
  char textCharacter(int column, int index) {
    if (borrowed == null) return storage.text(column)[index];
    return Utf8Text.trustedUtf16Character(
        borrowed.textSource(column), borrowed.textByteOffsetAt(column),
        borrowed.textByteLengthAt(column), index);
  }
  SqlValueAccess borrowedValues() { return borrowed; }
  boolean hasUtf8(int column) {
    return borrowed == null ? storage.hasUtf8(column)
        : !appendedColumn(column) && !nullValue(column)
            && borrowed.textByteLengthAt(column) >= 0;
  }
  int utf8Length(int column) {
    return borrowed == null ? storage.utf8Length(column)
        : borrowed.textByteLengthAt(column);
  }
  byte utf8ByteAt(int column, int index) {
    return borrowed == null ? storage.utf8(column)[index]
        : borrowed.textSource(column).getByte(
            borrowed.textByteOffsetAt(column) + index);
  }
  byte[] ownedUtf8(int column) { return borrowed == null && storage.hasUtf8(column)
      ? storage.utf8(column) : null; }
  BoundedByteSource utf8Slice(int column) {
    utf8Slice.column = column;
    return utf8Slice;
  }
  static int compareUtf8(
      SqlBlockRow left, int leftColumn, SqlBlockRow right, int rightColumn) {
    int leftLength = left.utf8Length(leftColumn);
    int rightLength = right.utf8Length(rightColumn);
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int compared = Integer.compare(
          Byte.toUnsignedInt(left.utf8ByteAt(leftColumn, index)),
          Byte.toUnsignedInt(right.utf8ByteAt(rightColumn, index)));
      if (compared != 0) return compared;
    }
    return Integer.compare(leftLength, rightLength);
  }
  StatusCode copyTextTo(int column, SqlBlockRow target, int targetColumn) {
    if (borrowed != null) {
      return target.setUtf8(
          targetColumn, borrowed.textSource(column),
          borrowed.textByteOffsetAt(column), borrowed.textByteLengthAt(column));
    }
    if (storage.hasUtf8(column)) {
      return target.setUtf8(
          targetColumn, storage.utf8(column), 0, storage.utf8Length(column));
    }
    return target.setText(targetColumn, text(column), 0, textLength(column));
  }
  private boolean appendedColumn(int column) {
    return appendedLogicalRowId && column == borrowed.count();
  }

  private final class Utf8Slice implements BoundedByteSource {
    private int column;
    @Override public int length() { return utf8Length(column); }
    @Override public byte getByte(int offset) { return utf8ByteAt(column, offset); }
  }
}
