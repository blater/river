package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;

/** Lazy retained character lanes for one reusable block row. */
final class SqlBlockRowTextStorage {
  private final SqlRetainedArrayAllocator allocator;
  private final SqlSessionShapeBudget budget;
  private char[][] lanes = new char[0][];
  private short[] lengths = new short[0];

  SqlBlockRowTextStorage(SqlRetainedArrayAllocator retainedAllocator) {
    this(retainedAllocator, null);
  }

  SqlBlockRowTextStorage(
      SqlRetainedArrayAllocator retainedAllocator, SqlSessionShapeBudget shapeBudget) {
    allocator = retainedAllocator;
    budget = shapeBudget;
  }

  char[][] lanes() { return lanes; }
  short[] lengths() { return lengths; }

  void publish(char[][] nextLanes, short[] nextLengths) {
    lanes = nextLanes;
    lengths = nextLengths;
  }

  void clear(int columns) {
    for (int column = 0; column < columns; column++) {
      clearValue(column);
    }
  }

  void clearValue(int column) {
    int length = length(column);
    if (lanes[column] != null) {
      for (int index = 0; index < length; index++) lanes[column][index] = 0;
    }
    lengths[column] = 0;
  }

  int length(int column) { return Short.toUnsignedInt(lengths[column]); }
  void length(int column, int length) { lengths[column] = (short) length; }

  StatusCode prepare(int column) {
    return prepare(column, CommandResult.MAXIMUM_TEXT_CHARACTERS);
  }

  StatusCode prepare(int column, int characters) {
    if (characters <= 0 || characters > CommandResult.MAXIMUM_TEXT_CHARACTERS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int current = lanes[column] == null ? 0 : lanes[column].length;
    if (current >= characters) return StatusCode.OK;
    long charged = (long) (characters - current) * Character.BYTES;
    StatusCode status = budget == null ? StatusCode.OK : budget.reserve(charged);
    if (!status.isOk()) return status;
    try {
      char[] next = allocator.characters(characters);
      if (current > 0) System.arraycopy(lanes[column], 0, next, 0, length(column));
      lanes[column] = next;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      if (budget != null) budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  char[] existing(int column) { return lanes[column]; }

  char[] text(int column) {
    return prepare(column).isOk() ? lanes[column] : null;
  }
}
