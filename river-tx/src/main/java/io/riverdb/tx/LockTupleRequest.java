package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import java.nio.ByteBuffer;

/** Validation and retained-byte geometry for borrowed tuple lock requests. */
final class LockTupleRequest {
  private LockTupleRequest() {
  }

  static boolean tuple(LockRequest request) {
    return request != null && (request.scope() == LockScope.TUPLE_KEY
        || request.scope() == LockScope.TUPLE_RANGE);
  }

  static boolean valid(LockRequest request) {
    if (!tuple(request)) return false;
    if (!slice(request.tupleLower(), request.tupleLowerOffset(), request.tupleLowerLength())) {
      return false;
    }
    if (request.scope() == LockScope.TUPLE_KEY) return request.tupleLower() != null;
    if (!slice(request.tupleUpper(), request.tupleUpperOffset(), request.tupleUpperLength())) {
      return false;
    }
    return true;
  }

  static long storedBytes(LockRequest request) {
    long lower = request.tupleLower() == null ? 0 : request.tupleLowerLength();
    long upper = request.scope() == LockScope.TUPLE_KEY || request.tupleUpper() == null
        ? 0 : request.tupleUpperLength();
    return lower + upper;
  }

  private static boolean slice(ByteBuffer buffer, int offset, int length) {
    if (buffer == null) return offset == 0 && length == 0;
    return offset >= 0 && length >= 0 && offset <= buffer.limit() - length;
  }
}
