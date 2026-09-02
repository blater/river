package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import java.nio.ByteBuffer;

/** Canonical tuple resource equality against one borrowed request. */
final class LockTupleResourceIdentity {
  boolean equal(
      LockExactResourceStore.Chunk chunk, int offset, LockRequest request) {
    if (chunk.tupleNamespaces[offset] != request.tupleNamespace()
        || chunk.tupleLowerLengths[offset] != nullableLength(
            request.tupleLower(), request.tupleLowerLength())
        || chunk.tupleUpperLengths[offset] != upperLength(request)) return false;
    if (request.scope() == LockScope.TUPLE_RANGE
        && (((chunk.tupleFlags[offset] & 1) != 0) != (request.tupleLower() != null
            && request.tupleLowerInclusive())
        || ((chunk.tupleFlags[offset] & 2) != 0) != (request.tupleUpper() != null
            && request.tupleUpperInclusive()))) return false;
    if (!equal(chunk.tupleLowerBytes[offset], request.tupleLower(),
        request.tupleLowerOffset(), request.tupleLowerLength())) return false;
    return request.scope() == LockScope.TUPLE_KEY
        || equal(chunk.tupleUpperBytes[offset], request.tupleUpper(),
            request.tupleUpperOffset(), request.tupleUpperLength());
  }

  private static boolean equal(
      byte[] stored, ByteBuffer requested, int requestedOffset, int requestedLength) {
    if (requested == null) return true;
    for (int index = 0; index < requestedLength; index++) {
      if (stored[index] != requested.get(requestedOffset + index)) return false;
    }
    return true;
  }

  private static int nullableLength(ByteBuffer buffer, int length) {
    return buffer == null ? -1 : length;
  }

  private static int upperLength(LockRequest request) {
    return request.scope() == LockScope.TUPLE_KEY ? -1
        : nullableLength(request.tupleUpper(), request.tupleUpperLength());
  }
}
