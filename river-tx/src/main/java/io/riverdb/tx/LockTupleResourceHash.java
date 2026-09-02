package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import java.nio.ByteBuffer;

/** Content hash for borrowed tuple resource identities. */
final class LockTupleResourceHash {
  private LockTupleResourceHash() {
  }

  static long hash(LockRequest request) {
    boolean range = request.scope() == LockScope.TUPLE_RANGE;
    long hash = LockExactDirectory.resourceHash(request.scope().ordinal(), request.tupleNamespace(),
        range && request.tupleLower() != null && request.tupleLowerInclusive() ? 1 : 0,
        range && request.tupleUpper() != null && request.tupleUpperInclusive() ? 1 : 0,
        LockTupleRequest.storedBytes(request));
    hash = append(hash, request.tupleLower(),
        request.tupleLowerOffset(), request.tupleLowerLength());
    return request.scope() == LockScope.TUPLE_KEY ? hash : append(
        hash, request.tupleUpper(), request.tupleUpperOffset(), request.tupleUpperLength());
  }

  private static long append(long hash, ByteBuffer bytes, int offset, int length) {
    if (bytes == null) return LockSlotIndex.hash(hash ^ -1L);
    long value = hash ^ length;
    for (int index = 0; index < length; index++) {
      value = Long.rotateLeft(value ^ Byte.toUnsignedLong(bytes.get(offset + index)), 11)
          * 0x9E3779B97F4A7C15L;
    }
    return LockSlotIndex.hash(value);
  }
}
