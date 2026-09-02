package io.riverdb.engine.sql;

import io.riverdb.base.column.ColumnBitSet;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Geometrically retained primitive block-row lanes with lazy text scratch. */
final class SqlBlockRowStorage {
  private final ColumnBitSet nulls = new ColumnBitSet();
  private final SqlBlockRowTextStorage text;
  private final SqlBlockRowLaneGrowth growth;
  private long[] highValues = new long[0];
  private long[] values = new long[0];
  private int count;
  private StatusCode status = StatusCode.OK;

  SqlBlockRowStorage() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlBlockRowStorage(SqlRetainedArrayAllocator retainedAllocator) {
    this(retainedAllocator, null);
  }

  SqlBlockRowStorage(
      SqlRetainedArrayAllocator retainedAllocator, SqlSessionShapeBudget shapeBudget) {
    text = new SqlBlockRowTextStorage(retainedAllocator, shapeBudget);
    growth = new SqlBlockRowLaneGrowth(retainedAllocator, shapeBudget);
  }

  StatusCode begin(int columns) {
    StatusCode admitted = reserve(columns);
    if (!admitted.isOk()) {
      status = admitted;
      return admitted;
    }
    clear();
    status = nulls.clearForSize(columns);
    if (status.isOk()) count = columns;
    return status;
  }

  void clear() {
    for (int column = 0; column < count; column++) {
      values[column] = 0;
      highValues[column] = 0;
    }
    text.clear(count);
    nulls.reset();
    count = 0;
    status = StatusCode.OK;
  }

  StatusCode status() { return status; }
  int count() { return count; }
  long value(int column) { return values[column]; }
  long highValue(int column) { return highValues[column]; }
  void value(int column, long value) {
    value(column, value >> 63, value);
  }
  void value(int column, long high, long value) {
    if (valid(column)) {
      highValues[column] = high;
      values[column] = value;
    }
  }
  boolean isNull(int column) { return nulls.get(column); }
  void setNull(int column) {
    if (valid(column)) {
      highValues[column] = 0;
      values[column] = 0;
      nulls.set(column);
    }
  }
  void clearValue(int column) {
    if (!valid(column)) return;
    highValues[column] = 0;
    values[column] = 0;
    text.clearValue(column);
    nulls.clear(column);
  }
  long nullWord(int word) { return nulls.word(word); }
  int nullWordCount() { return nulls.wordCount(); }
  int textLength(int column) { return text.length(column); }
  void textLength(int column, int length) { if (valid(column)) text.length(column, length); }

  StatusCode prepareText(int column) {
    return prepareText(column, io.riverdb.engine.api.CommandResult.MAXIMUM_TEXT_CHARACTERS);
  }

  StatusCode prepareText(int column, int characters) {
    if (!valid(column)) return status.isOk()
        ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    StatusCode prepared = text.prepare(column, characters);
    if (!prepared.isOk()) status = prepared;
    return prepared;
  }

  char[] text(int column) {
    if (valid(column) && text.existing(column) == null) prepareText(column);
    return valid(column) ? text.existing(column) : null;
  }

  StatusCode copyFrom(SqlBlockRowStorage source) {
    return SqlBlockRowStorageCopy.copy(source, this);
  }

  char[] existingText(int column) { return text.existing(column); }

  private boolean valid(int column) {
    return status.isOk() && column >= 0 && column < count;
  }

  private StatusCode reserve(int required) {
    if (required < 0 || required > SqlShapeLimits.MAX_RESULT_COLUMNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode admitted = nulls.reserve(required, SqlShapeLimits.MAX_RESULT_COLUMNS);
    if (!admitted.isOk()) return admitted;
    StatusCode status = growth.grow(values, highValues, text, required);
    if (status.isOk()) {
      values = growth.values();
      highValues = growth.highValues();
    }
    return status;
  }
}
