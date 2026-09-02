package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import java.nio.ByteBuffer;

/** Endpoint shape access without materializing endpoint objects. */
final class LockTupleEndpoint {
  static final int NEGATIVE_INFINITY = 0;
  static final int BEFORE_PREFIX = 1;
  static final int VALUE = 2;
  static final int AFTER_VALUE = 3;
  static final int AFTER_PREFIX = 4;
  static final int POSITIVE_INFINITY = 5;

  private LockTupleEndpoint() {
  }

  static byte[] bytes(LockExactResourceStore.Chunk chunk, int at, boolean upper) {
    return upper && chunk.scopes[at] == LockScope.TUPLE_RANGE.ordinal()
        ? chunk.tupleUpperBytes[at] : chunk.tupleLowerBytes[at];
  }

  static int length(LockExactResourceStore.Chunk chunk, int at, boolean upper) {
    return upper && chunk.scopes[at] == LockScope.TUPLE_RANGE.ordinal()
        ? chunk.tupleUpperLengths[at] : chunk.tupleLowerLengths[at];
  }

  static int kind(LockExactResourceStore.Chunk chunk, int at, boolean upper) {
    if (chunk.scopes[at] == LockScope.TUPLE_KEY.ordinal()) return upper ? AFTER_VALUE : VALUE;
    int length = upper ? chunk.tupleUpperLengths[at] : chunk.tupleLowerLengths[at];
    if (length < 0) return upper ? POSITIVE_INFINITY : NEGATIVE_INFINITY;
    boolean inclusive = upper ? (chunk.tupleFlags[at] & 2) != 0
        : (chunk.tupleFlags[at] & 1) != 0;
    return inclusive ? (upper ? AFTER_PREFIX : BEFORE_PREFIX)
        : (upper ? BEFORE_PREFIX : AFTER_PREFIX);
  }

  static ByteBuffer buffer(LockRequest request, boolean upper) {
    return request.scope() == LockScope.TUPLE_KEY || !upper
        ? request.tupleLower() : request.tupleUpper();
  }

  static int offset(LockRequest request, boolean upper) {
    return request.scope() == LockScope.TUPLE_KEY || !upper
        ? request.tupleLowerOffset() : request.tupleUpperOffset();
  }

  static int length(LockRequest request, boolean upper) {
    if (buffer(request, upper) == null) return -1;
    return request.scope() == LockScope.TUPLE_KEY || !upper
        ? request.tupleLowerLength() : request.tupleUpperLength();
  }

  static int kind(LockRequest request, boolean upper) {
    if (request.scope() == LockScope.TUPLE_KEY) return upper ? AFTER_VALUE : VALUE;
    if (buffer(request, upper) == null) return upper ? POSITIVE_INFINITY : NEGATIVE_INFINITY;
    boolean inclusive = upper ? request.tupleUpperInclusive() : request.tupleLowerInclusive();
    return inclusive ? (upper ? AFTER_PREFIX : BEFORE_PREFIX)
        : (upper ? BEFORE_PREFIX : AFTER_PREFIX);
  }
}
