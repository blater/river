package io.riverdb.engine.relational;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.schema.KeyDescriptor;
import java.nio.ByteBuffer;

/** Atomically replaces all arrays required for one tuple-plan capacity growth. */
final class RelationalDescriptorTupleDeltaGrowth {
  StatusCode reserve(
      RelationalDescriptorTupleDeltaStorage storage,
      int requiredKeys, int requiredBytes, int requiredUserBytes) {
    int keyCapacity = capacity(
        storage.keysArray().length, requiredKeys,
        RelationalDescriptorTupleDeltaStorage.MAXIMUM_KEYS,
        RelationalDescriptorTupleDeltaStorage.INITIAL_KEYS);
    int byteCapacity = capacity(
        storage.bytesArray().length, requiredBytes,
        RelationalDescriptorTupleDeltaStorage.MAXIMUM_BYTES,
        RelationalDescriptorTupleDeltaStorage.INITIAL_BYTES);
    int userCapacity = capacity(
        storage.userBytesArray().length, requiredUserBytes,
        SqlShapeLimits.MAX_INDEX_USER_KEY_BYTES,
        RelationalDescriptorTupleDeltaStorage.INITIAL_BYTES);
    if (keyCapacity < 0 || byteCapacity < 0 || userCapacity < 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long replacement = bytes(storage, keyCapacity, byteCapacity, userCapacity, true);
    if (replacement == 0) return StatusCode.OK;
    RelationalRetainedBudget budget = storage.budget();
    StatusCode status = budget == null ? StatusCode.OK : budget.reserve(replacement);
    if (!status.isOk()) return status;
    try {
      return allocate(storage, keyCapacity, byteCapacity, userCapacity, replacement);
    } catch (OutOfMemoryError failure) {
      if (budget != null) budget.rollback(replacement);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode allocate(
      RelationalDescriptorTupleDeltaStorage storage,
      int keyCapacity, int byteCapacity, int userCapacity, long replacement) {
    RelationalDescriptorTupleDeltaAllocator allocator = storage.allocator();
    boolean growKeys = keyCapacity != storage.keysArray().length;
    KeyDescriptor[] keys = growKeys ? allocator.keys(keyCapacity) : storage.keysArray();
    int[] beforeOffsets = growKeys ? allocator.integers(keyCapacity) : storage.beforeOffsetsArray();
    int[] beforeLengths = growKeys ? allocator.integers(keyCapacity) : storage.beforeLengthsArray();
    int[] afterOffsets = growKeys ? allocator.integers(keyCapacity) : storage.afterOffsetsArray();
    int[] afterLengths = growKeys ? allocator.integers(keyCapacity) : storage.afterLengthsArray();
    byte[] retained = byteCapacity == storage.bytesArray().length
        ? storage.bytesArray() : allocator.bytes(byteCapacity);
    ByteBuffer retainedView = retained == storage.bytesArray()
        ? storage.byteView() : allocator.view(retained);
    byte[] user = userCapacity == storage.userBytesArray().length
        ? storage.userBytesArray() : allocator.bytes(userCapacity);
    ByteBuffer userView = user == storage.userBytesArray()
        ? storage.userView() : allocator.view(user);
    long retired = bytes(storage, keyCapacity, byteCapacity, userCapacity, false);
    storage.publish(
        keys, beforeOffsets, beforeLengths, afterOffsets, afterLengths,
        retained, retainedView, user, userView);
    if (storage.budget() != null && retired > 0) storage.budget().rollback(retired);
    return StatusCode.OK;
  }

  private static long bytes(
      RelationalDescriptorTupleDeltaStorage storage,
      int keyCapacity, int byteCapacity, int userCapacity, boolean replacement) {
    int keys = replacement ? keyCapacity : storage.keysArray().length;
    int retained = replacement ? byteCapacity : storage.bytesArray().length;
    int user = replacement ? userCapacity : storage.userBytesArray().length;
    long result = keyCapacity == storage.keysArray().length ? 0
        : keys * (long) (Long.BYTES + 4 * Integer.BYTES);
    if (byteCapacity != storage.bytesArray().length) result += retained;
    if (userCapacity != storage.userBytesArray().length) result += user;
    return result;
  }

  private static int capacity(int current, int required, int maximum, int initial) {
    return required <= current ? current
        : BoundedArrayGrowth.capacity(current, required, maximum, initial);
  }
}
