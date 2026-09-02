package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Bounded retained slices for one descriptor row's before/after physical index keys. */
final class RelationalDescriptorTupleDeltaStorage {
  static final int MAXIMUM_KEYS = SqlShapeLimits.MAX_TABLE_INDEXES;
  static final int MAXIMUM_BYTES =
      2 * MAXIMUM_KEYS * SqlShapeLimits.MAX_PHYSICAL_INDEX_KEY_BYTES;
  static final int INITIAL_KEYS = 8;
  static final int INITIAL_BYTES = 512;
  private final RelationalRetainedBudget budget;
  private final RelationalDescriptorTupleDeltaAllocator allocator;
  private final RelationalDescriptorTupleDeltaGrowth growth =
      new RelationalDescriptorTupleDeltaGrowth();
  private KeyDescriptor[] keys = new KeyDescriptor[0];
  private int[] beforeOffsets = new int[0];
  private int[] beforeLengths = new int[0];
  private int[] afterOffsets = new int[0];
  private int[] afterLengths = new int[0];
  private byte[] bytes = new byte[0];
  private byte[] userBytes = new byte[0];
  private ByteBuffer byteView = ByteBuffer.wrap(bytes);
  private ByteBuffer userView = ByteBuffer.wrap(userBytes);
  private int keyCount;
  private int used;
  private int userUsed;

  RelationalDescriptorTupleDeltaStorage(
      RelationalRetainedBudget retainedBudget,
      RelationalDescriptorTupleDeltaAllocator deltaAllocator) {
    budget = retainedBudget;
    allocator = deltaAllocator;
  }

  StatusCode reserve(int requiredKeys, int requiredBytes, int requiredUserBytes) {
    if (requiredKeys <= 0 || requiredKeys > MAXIMUM_KEYS
        || requiredBytes <= 0 || requiredBytes > MAXIMUM_BYTES
        || requiredUserBytes <= 0
        || requiredUserBytes > SqlShapeLimits.MAX_INDEX_USER_KEY_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return growth.reserve(this, requiredKeys, requiredBytes, requiredUserBytes);
  }

  void reset() {
    for (int index = 0; index < keyCount; index++) {
      keys[index] = null;
      beforeOffsets[index] = 0;
      beforeLengths[index] = 0;
      afterOffsets[index] = 0;
      afterLengths[index] = 0;
    }
    for (int index = 0; index < used; index++) bytes[index] = 0;
    for (int index = 0; index < userUsed; index++) userBytes[index] = 0;
    keyCount = 0;
    used = 0;
    userUsed = 0;
  }

  void keyCount(int value) { keyCount = value; }
  int keyCount() { return keyCount; }
  KeyDescriptor keyAt(int index) { return keys[index]; }
  void keyAt(int index, KeyDescriptor value) { keys[index] = value; }
  int beforeOffsetAt(int index) { return beforeOffsets[index]; }
  int beforeLengthAt(int index) { return beforeLengths[index]; }
  int afterOffsetAt(int index) { return afterOffsets[index]; }
  int afterLengthAt(int index) { return afterLengths[index]; }
  ByteBuffer bytes() { return byteView; }
  int usedBytes() { return used; }

  void copyBefore(int index, ByteBuffer source, int length) {
    beforeOffsets[index] = copy(source, length);
    beforeLengths[index] = length;
  }

  void copyAfter(int index, ByteBuffer source, int length) {
    afterOffsets[index] = copy(source, length);
    afterLengths[index] = length;
  }

  void shareAfter(int index) {
    afterOffsets[index] = beforeOffsets[index];
    afterLengths[index] = beforeLengths[index];
  }

  ByteBuffer userKey(int index, boolean after) {
    int offset = after ? afterOffsets[index] : beforeOffsets[index];
    int length = (after ? afterLengths[index] : beforeLengths[index])
        - TupleKeyCodec.LOGICAL_ROW_ID_BYTES;
    for (int cursor = 0; cursor < length; cursor++) {
      userBytes[cursor] = bytes[offset + cursor];
    }
    userBytes[1] = (byte) (userBytes[1] & ~TupleKeyCodec.FLAG_PHYSICAL);
    userUsed = Math.max(userUsed, length);
    return userView;
  }

  int userLength(int index, boolean after) {
    return (after ? afterLengths[index] : beforeLengths[index])
        - TupleKeyCodec.LOGICAL_ROW_ID_BYTES;
  }

  private int copy(ByteBuffer source, int length) {
    int offset = used;
    for (int index = 0; index < length; index++) bytes[used + index] = source.get(index);
    used += length;
    return offset;
  }

  KeyDescriptor[] keysArray() { return keys; }
  int[] beforeOffsetsArray() { return beforeOffsets; }
  int[] beforeLengthsArray() { return beforeLengths; }
  int[] afterOffsetsArray() { return afterOffsets; }
  int[] afterLengthsArray() { return afterLengths; }
  byte[] bytesArray() { return bytes; }
  byte[] userBytesArray() { return userBytes; }
  ByteBuffer byteView() { return byteView; }
  ByteBuffer userView() { return userView; }
  RelationalRetainedBudget budget() { return budget; }
  RelationalDescriptorTupleDeltaAllocator allocator() { return allocator; }

  void publish(
      KeyDescriptor[] nextKeys,
      int[] nextBeforeOffsets, int[] nextBeforeLengths,
      int[] nextAfterOffsets, int[] nextAfterLengths,
      byte[] nextBytes, ByteBuffer nextByteView,
      byte[] nextUserBytes, ByteBuffer nextUserView) {
    keys = nextKeys;
    beforeOffsets = nextBeforeOffsets;
    beforeLengths = nextBeforeLengths;
    afterOffsets = nextAfterOffsets;
    afterLengths = nextAfterLengths;
    bytes = nextBytes;
    byteView = nextByteView;
    userBytes = nextUserBytes;
    userView = nextUserView;
  }
}
