package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.column.ColumnBitSet;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.sql.SqlIdentifier;

/** Actual-count retained columns and multiword nullability for a block schema. */
final class SqlBlockSchemaColumns {
  private static final long CHARGED_BYTES_PER_LANE = 176;
  private final SqlSessionShapeBudget budget;
  private final ColumnBitSet nullable = new ColumnBitSet();
  private Name[] names = new Name[0];
  private int[] descriptors = new int[0];
  private int count;
  private StatusCode status = StatusCode.OK;

  SqlBlockSchemaColumns(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  void reset() {
    for (int index = 0; index < count; index++) {
      names[index].reset();
      descriptors[index] = 0;
    }
    nullable.reset();
    count = 0;
    status = StatusCode.OK;
  }

  void begin(int columns) {
    StatusCode admitted = reserve(columns);
    if (!admitted.isOk()) {
      status = admitted;
      return;
    }
    reset();
    status = nullable.clearForSize(columns);
    if (status.isOk()) count = columns;
  }

  void set(int column, CharSequence name, int descriptor, boolean isNullable) {
    if (!status.isOk() || column < 0 || column >= count) return;
    names[column].set(name);
    descriptors[column] = descriptor;
    if (isNullable) nullable.set(column);
    else nullable.clear(column);
  }

  StatusCode status() { return status; }
  int count() { return count; }
  int descriptor(int column) { return descriptors[column]; }
  boolean nullable(int column) { return nullable.get(column); }
  CharSequence name(int column) { return names[column]; }

  private StatusCode reserve(int required) {
    if (required < 0 || required > SqlShapeLimits.MAX_RESULT_COLUMNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode admitted = reserveNullWords(required);
    if (!admitted.isOk() || required <= names.length) return admitted;
    int capacity = BoundedArrayGrowth.capacity(
        names.length, required, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    long charged = (capacity - names.length) * CHARGED_BYTES_PER_LANE;
    admitted = budget.reserve(charged);
    if (!admitted.isOk()) return admitted;
    try {
      Name[] nextNames = new Name[capacity];
      int[] nextDescriptors = new int[capacity];
      System.arraycopy(names, 0, nextNames, 0, names.length);
      System.arraycopy(descriptors, 0, nextDescriptors, 0, descriptors.length);
      for (int index = names.length; index < capacity; index++) {
        nextNames[index] = new Name();
      }
      names = nextNames;
      descriptors = nextDescriptors;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode reserveNullWords(int required) {
    int currentWords = nullable.capacity() / Long.SIZE;
    int requiredWords = (required + Long.SIZE - 1) / Long.SIZE;
    if (requiredWords <= currentWords) return StatusCode.OK;
    int maximumWords =
        (SqlShapeLimits.MAX_RESULT_COLUMNS + Long.SIZE - 1) / Long.SIZE;
    int capacity = BoundedArrayGrowth.capacity(currentWords, requiredWords, maximumWords, 1);
    long charged = (capacity - currentWords) * Long.BYTES;
    StatusCode admitted = budget.reserve(charged);
    if (!admitted.isOk()) return admitted;
    admitted = nullable.reserve(required, SqlShapeLimits.MAX_RESULT_COLUMNS);
    if (!admitted.isOk()) budget.rollback(charged);
    return admitted;
  }

  private static final class Name implements CharSequence {
    private final char[] value = new char[SqlIdentifier.MAXIMUM_LENGTH];
    private int length;
    void reset() { length = 0; }
    void set(CharSequence source) {
      length = source == null ? 0 : source.length();
      for (int index = 0; index < length; index++) value[index] = source.charAt(index);
    }
    @Override public int length() { return length; }
    @Override public char charAt(int index) { return value[index]; }
    @Override public CharSequence subSequence(int start, int end) {
      return new String(value, start, end - start);
    }
  }
}
