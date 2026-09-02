package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Synchronous evaluator scratch; retained boundaries immediately encode canonical UTF-8. */
final class SqlBlockRow implements SqlNullWords {
  private final SqlBlockRowStorage storage;
  private long key;

  SqlBlockRow() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlBlockRow(SqlRetainedArrayAllocator allocator) {
    storage = new SqlBlockRowStorage(allocator);
  }

  SqlBlockRow(SqlSessionShapeBudget budget) {
    storage = new SqlBlockRowStorage(SqlRetainedArrayAllocator.STANDARD, budget);
  }

  StatusCode reset(int columns) {
    key = 0;
    return storage.begin(columns);
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
  void setTextLength(int column, int length) { storage.textLength(column, length); }
  void clearValue(int column) { storage.clearValue(column); }
  StatusCode copyFrom(SqlBlockRow source) {
    StatusCode status = storage.copyFrom(source.storage);
    if (status.isOk()) key = source.key;
    return status;
  }
  void setKey(long value) { key = value; }
  long key() { return key; }
  StatusCode status() { return storage.status(); }
  long value(int column) { return storage.value(column); }
  long highValue(int column) { return storage.highValue(column); }
  boolean nullValue(int column) { return storage.isNull(column); }
  @Override public long nullWord(int word) { return storage.nullWord(word); }
  @Override public int nullWordCount() { return storage.nullWordCount(); }
  int count() { return storage.count(); }
  int textLength(int column) { return storage.textLength(column); }
  StatusCode prepareText(int column) { return storage.prepareText(column); }
  StatusCode prepareText(int column, int characters) {
    return storage.prepareText(column, characters);
  }
  char[] text(int column) { return storage.text(column); }
  char textCharacter(int column, int index) { return storage.text(column)[index]; }
}
