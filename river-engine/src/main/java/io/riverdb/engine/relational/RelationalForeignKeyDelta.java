package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import java.nio.ByteBuffer;

/** Reusable before/after classification for outbound foreign-key validation. */
final class RelationalForeignKeyDelta {
  private final RelationalTupleKeyEncoder before = new RelationalTupleKeyEncoder();
  private final RelationalTupleKeyEncoder after = new RelationalTupleKeyEncoder();
  private boolean[] changed = new boolean[0];
  private int count;

  StatusCode prepare(
      TableDescriptor table, SqlValueBuffer beforeValues, SqlValueBuffer afterValues) {
    if (table == null || beforeValues == null || afterValues == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    reset();
    StatusCode status = reserve(table.foreignKeyCount());
    if (status.isOk()) count = table.foreignKeyCount();
    for (int index = 0; status.isOk() && index < table.foreignKeyCount(); index++) {
      KeyDescriptor foreign = table.foreignKeyAt(index);
      status = before.encodeUser(foreign, beforeValues);
      if (status.isOk()) status = after.encodeUser(foreign, afterValues);
      if (status.isOk()) changed[index] = !equal(before, after);
    }
    if (!status.isOk()) reset();
    return status;
  }

  boolean changedAt(int index) {
    return index >= 0 && index < count && changed[index];
  }

  void reset() {
    for (int index = 0; index < count; index++) changed[index] = false;
    count = 0;
  }

  private StatusCode reserve(int required) {
    if (required <= changed.length) return StatusCode.OK;
    int capacity = Math.max(1, changed.length);
    while (capacity < required) {
      int grown = capacity <= Integer.MAX_VALUE / 2 ? capacity << 1 : Integer.MAX_VALUE;
      if (grown == capacity) return StatusCode.RESOURCE_EXHAUSTED;
      capacity = grown;
    }
    try {
      changed = new boolean[capacity];
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static boolean equal(
      RelationalTupleKeyEncoder left, RelationalTupleKeyEncoder right) {
    if (left.length() != right.length()) return false;
    ByteBuffer leftBytes = left.bytes();
    ByteBuffer rightBytes = right.bytes();
    for (int index = 0; index < left.length(); index++) {
      if (leftBytes.get(index) != rightBytes.get(index)) return false;
    }
    return true;
  }
}
