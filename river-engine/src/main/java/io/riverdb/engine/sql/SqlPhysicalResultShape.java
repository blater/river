package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.column.ColumnBitSet;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.sql.SqlIdentifier;

/** Geometrically retained physical result metadata with multiword nullability. */
final class SqlPhysicalResultShape {
  private static final long CHARGED_BYTES_PER_LANE = 176;
  private final SqlSessionShapeBudget budget;
  private final ColumnBitSet nullable = new ColumnBitSet();
  private int[] projections = new int[0];
  private int[] descriptors = new int[0];
  private Name[] names = new Name[0];
  private int count;
  private StatusCode status = StatusCode.OK;

  SqlPhysicalResultShape(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  void reset() {
    for (int index = 0; index < count; index++) {
      projections[index] = 0;
      descriptors[index] = 0;
      names[index].reset();
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

  void set(int index, int projection, int descriptor, CharSequence name) {
    if (!status.isOk()) return;
    if (index < 0 || index >= count) {
      status = StatusCode.CONFLICT;
      return;
    }
    projections[index] = projection;
    descriptors[index] = descriptor;
    names[index].copyFrom(name);
  }

  void setNullable(int index, boolean value) {
    if (!status.isOk() || index < 0 || index >= count) return;
    if (value) nullable.set(index);
    else nullable.clear(index);
  }

  StatusCode status() { return status; }
  int count() { return count; }
  int projection(int index) { return valid(index) ? projections[index] : -1; }
  int descriptor(int index) { return valid(index) ? descriptors[index] : 0; }
  CharSequence name(int index) { return valid(index) ? names[index] : null; }
  int nameLength(int index) { return valid(index) ? names[index].length() : 0; }
  boolean isNullable(int index) { return valid(index) && nullable.get(index); }
  long nullableWord(int word) { return nullable.word(word); }
  int nullableWordCount() { return nullable.wordCount(); }
  int[] projections() { return projections; }
  int[] descriptors() { return descriptors; }

  int[] copyDescriptors(int[] destination, int columns) {
    System.arraycopy(descriptors, 0, destination, 0, columns);
    return destination;
  }

  private boolean valid(int index) { return index >= 0 && index < count; }

  private StatusCode reserve(int required) {
    if (required < 0 || required > SqlShapeLimits.MAX_RESULT_COLUMNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode admitted = reserveNullWords(required);
    if (!admitted.isOk() || required <= projections.length) return admitted;
    int capacity = BoundedArrayGrowth.capacity(
        projections.length, required, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    long charged = (capacity - projections.length) * CHARGED_BYTES_PER_LANE;
    admitted = budget.reserve(charged);
    if (!admitted.isOk()) return admitted;
    try {
      int[] nextProjections = new int[capacity];
      int[] nextDescriptors = new int[capacity];
      Name[] nextNames = new Name[capacity];
      System.arraycopy(projections, 0, nextProjections, 0, projections.length);
      System.arraycopy(descriptors, 0, nextDescriptors, 0, descriptors.length);
      System.arraycopy(names, 0, nextNames, 0, names.length);
      for (int index = names.length; index < capacity; index++) {
        nextNames[index] = new Name();
      }
      projections = nextProjections;
      descriptors = nextDescriptors;
      names = nextNames;
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
    void copyFrom(CharSequence source) {
      length = source == null ? 0 : source.length();
      for (int index = 0; index < length; index++) value[index] = source.charAt(index);
    }
    @Override public int length() { return length; }
    @Override public char charAt(int index) { return value[index]; }
    @Override public CharSequence subSequence(int start, int end) {
      return new String(value, start, end - start);
    }
    @Override public String toString() { return new String(value, 0, length); }
  }
}
