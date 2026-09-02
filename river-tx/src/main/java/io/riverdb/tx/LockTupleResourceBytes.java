package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Bounded reusable byte ownership for canonical tuple resources. */
final class LockTupleResourceBytes {
  private static final int BYTE_ARRAY_OVERHEAD = 16;
  private static final byte LOWER_INCLUSIVE = 1;
  private static final byte UPPER_INCLUSIVE = 2;
  private final LockSegmentArena arena;

  LockTupleResourceBytes(LockSegmentArena owner) { arena = owner; }

  StatusCode prepare(
      LockExactResourceStore.Chunk chunk, int offset, LockRequest request) {
    if (!LockTupleRequest.tuple(request)) return StatusCode.OK;
    int lower = request.tupleLower() == null ? 0 : request.tupleLowerLength();
    int upper = request.scope() == LockScope.TUPLE_KEY || request.tupleUpper() == null
        ? 0 : request.tupleUpperLength();
    byte[] currentLower = chunk.tupleLowerBytes[offset];
    byte[] currentUpper = chunk.tupleUpperBytes[offset];
    int lowerCapacity = capacity(lower, currentLower);
    int upperCapacity = capacity(upper, currentUpper);
    long growth = retained(lowerCapacity) - retained(currentLower)
        + retained(upperCapacity) - retained(currentUpper);
    if (growth == 0) return StatusCode.OK;
    StatusCode status = arena.reserve(growth);
    if (!status.isOk()) return status;
    try {
      byte[] replacementLower = lowerCapacity == length(currentLower)
          ? currentLower : new byte[lowerCapacity];
      byte[] replacementUpper = upperCapacity == length(currentUpper)
          ? currentUpper : new byte[upperCapacity];
      chunk.tupleLowerBytes[offset] = replacementLower;
      chunk.tupleUpperBytes[offset] = replacementUpper;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      arena.release(growth);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void initialize(
      LockExactResourceStore.Chunk chunk, int offset, LockRequest request) {
    chunk.tupleNamespaces[offset] = request.tupleNamespace();
    chunk.tupleLowerLengths[offset] = request.tupleLower() == null
        ? -1 : request.tupleLowerLength();
    chunk.tupleUpperLengths[offset] = request.tupleUpper() == null
        ? -1 : request.tupleUpperLength();
    boolean range = request.scope() == LockScope.TUPLE_RANGE;
    chunk.tupleFlags[offset] = (byte) ((range && request.tupleLower() != null
        && request.tupleLowerInclusive() ? LOWER_INCLUSIVE : 0)
        | (range && request.tupleUpper() != null
            && request.tupleUpperInclusive() ? UPPER_INCLUSIVE : 0));
    copy(request.tupleLower(), request.tupleLowerOffset(), request.tupleLowerLength(),
        chunk.tupleLowerBytes[offset]);
    copy(request.tupleUpper(), request.tupleUpperOffset(), request.tupleUpperLength(),
        chunk.tupleUpperBytes[offset]);
  }

  void release(LockExactResourceStore.Chunk chunk) {
    release(chunk.tupleLowerBytes);
    release(chunk.tupleUpperBytes);
  }

  void clear(LockExactResourceStore.Chunk chunk, int offset) {
    clear(chunk.tupleLowerBytes[offset], chunk.tupleLowerLengths[offset]);
    clear(chunk.tupleUpperBytes[offset], chunk.tupleUpperLengths[offset]);
  }

  static boolean tupleScope(byte scope) {
    return scope == LockScope.TUPLE_KEY.ordinal() || scope == LockScope.TUPLE_RANGE.ordinal();
  }

  private void release(byte[][] retained) {
    for (byte[] bytes : retained) if (bytes != null) arena.release(retained(bytes));
  }

  private static int capacity(int required, byte[] current) {
    if (required <= length(current)) return length(current);
    if (required <= 64) return 64;
    int highest = Integer.highestOneBit(required - 1);
    return highest <= (1 << 29) ? highest << 1 : required;
  }

  private static int length(byte[] bytes) { return bytes == null ? 0 : bytes.length; }

  private static long retained(byte[] bytes) { return bytes == null ? 0 : retained(bytes.length); }

  private static long retained(int capacity) {
    return capacity == 0 ? 0 : (long) BYTE_ARRAY_OVERHEAD + capacity;
  }

  private static void clear(byte[] bytes, int used) {
    if (bytes != null && used > 0) Arrays.fill(bytes, 0, used, (byte) 0);
  }

  private static void copy(ByteBuffer source, int offset, int length, byte[] target) {
    if (source == null) return;
    for (int index = 0; index < length; index++) target[index] = source.get(offset + index);
  }
}
