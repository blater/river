package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Durable encodings for catalog allocation, user, and identity sequences. */
final class CatalogSequenceCodec {
  private static final long ALLOCATION_MAGIC = 0x5249564552534551L; // RIVERSEQ
  private static final long USER_MAGIC = 0x5249564552555345L; // RIVERUSE
  private static final long IDENTITY_MAGIC = 0x5249564552494453L; // RIVERIDS
  private static final int VERSION = 1;

  private CatalogSequenceCodec() {
  }

  static void encodeAllocation(ByteBuffer target, int nextTableId) {
    clear(target);
    target.putLong(0, ALLOCATION_MAGIC);
    target.putInt(8, VERSION);
    target.putInt(12, nextTableId);
    target.position(0);
    target.limit(16);
  }

  static StatusCode decodeAllocation(
      HeapRowResult source, ByteBuffer scratch, IntResult result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()
        || source.length() != 16
        || scratch.getLong(0) != ALLOCATION_MAGIC
        || scratch.getInt(8) != VERSION
        || scratch.getInt(12) <= 0) {
      return StatusCode.CORRUPTION;
    }
    result.set(scratch.getInt(12));
    return StatusCode.OK;
  }

  static void encodeUser(
      ByteBuffer target,
      CharSequence name,
      long nextValue,
      long increment,
      boolean exhausted) {
    clear(target);
    target.putLong(0, USER_MAGIC);
    target.putInt(8, VERSION);
    target.putInt(12, name.length());
    target.putLong(16, nextValue);
    target.putLong(24, increment);
    target.putInt(32, exhausted ? 1 : 0);
    for (int index = 0; index < name.length(); index++) {
      target.put(36 + index, (byte) name.charAt(index));
    }
    target.position(0);
    target.limit(36 + name.length());
  }

  static StatusCode decodeUser(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      SequenceResult result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) {
      return status;
    }
    if (source.length() < Long.BYTES) {
      return StatusCode.CORRUPTION;
    }
    if (scratch.getLong(0) != USER_MAGIC) {
      return StatusCode.CONFLICT;
    }
    int nameBytes = source.length() >= 16 ? scratch.getInt(12) : -1;
    long increment = source.length() >= 32 ? scratch.getLong(24) : 0;
    int exhausted = source.length() >= 36 ? scratch.getInt(32) : -1;
    if (source.length() < 37
        || scratch.getInt(8) != VERSION
        || nameBytes <= 0
        || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || source.length() != 36 + nameBytes
        || increment == 0
        || (exhausted != 0 && exhausted != 1)
        || nameBytes != expectedName.length()) {
      return StatusCode.CORRUPTION;
    }
    for (int index = 0; index < nameBytes; index++) {
      if (Byte.toUnsignedInt(scratch.get(36 + index))
          != expectedName.charAt(index)) {
        return StatusCode.CONFLICT;
      }
    }
    result.set(scratch.getLong(16), increment, exhausted == 1);
    return StatusCode.OK;
  }

  static void encodeIdentity(
      ByteBuffer target, int tableId, long nextValue, boolean exhausted) {
    clear(target);
    target.putLong(0, IDENTITY_MAGIC);
    target.putInt(8, VERSION);
    target.putInt(12, tableId);
    target.putLong(16, nextValue);
    target.putInt(24, exhausted ? 1 : 0);
    target.position(0);
    target.limit(28);
  }

  static StatusCode decodeIdentity(
      HeapRowResult source,
      ByteBuffer scratch,
      int expectedTableId,
      SequenceResult result) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) {
      return status;
    }
    int exhausted = source.length() >= 28 ? scratch.getInt(24) : -1;
    if (source.length() != 28
        || scratch.getLong(0) != IDENTITY_MAGIC
        || scratch.getInt(8) != VERSION
        || scratch.getInt(12) != expectedTableId
        || scratch.getLong(16) < 1
        || scratch.getLong(16) > RelationalKey.MAXIMUM_USER_KEY
        || (exhausted != 0 && exhausted != 1)) {
      return StatusCode.CORRUPTION;
    }
    result.set(scratch.getLong(16), 1, exhausted == 1);
    return StatusCode.OK;
  }

  static boolean matchesMagic(long magic) {
    return magic == ALLOCATION_MAGIC || magic == USER_MAGIC || magic == IDENTITY_MAGIC;
  }

  private static void clear(ByteBuffer target) {
    target.clear();
    for (int index = 0; index < target.capacity(); index++) {
      target.put(index, (byte) 0);
    }
  }

  static final class IntResult {
    private int value;

    void set(int result) {
      value = result;
    }

    int value() {
      return value;
    }
  }

  static final class SequenceResult {
    private long nextValue;
    private long increment;
    private boolean exhausted;

    void set(long value, long step, boolean isExhausted) {
      nextValue = value;
      increment = step;
      exhausted = isExhausted;
    }

    long nextValue() {
      return nextValue;
    }

    long increment() {
      return increment;
    }

    boolean isExhausted() {
      return exhausted;
    }
  }
}
