package io.riverdb.tx;

import io.riverdb.base.key.OrderedKey;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;

/** Total endpoint order shared by scalar and variable-length tuple intervals. */
final class LockIntervalOrder {
  private final LockExactResourceStore resources;
  private final LockTupleEndpointOrder tuples;

  LockIntervalOrder(LockExactResourceStore store) {
    resources = store;
    tuples = new LockTupleEndpointOrder(store);
  }

  int compare(long left, long right) {
    int compared = compare(left, false, right, false);
    if (compared == 0) compared = compare(left, true, right, true);
    return compared == 0 ? Long.compare(left, right) : compared;
  }

  boolean maximumAfter(long maximum, LockRequest query) {
    return maximum >= 0 && compare(maximum, true, query, false) > 0;
  }

  boolean maximumAfter(long maximum, long query) {
    return maximum >= 0 && compare(maximum, true, query, false) > 0;
  }

  boolean lowerBeforeUpper(long resource, LockRequest query) {
    return compare(resource, false, query, true) < 0;
  }

  boolean lowerBeforeUpper(long resource, long query) {
    return compare(resource, false, query, true) < 0;
  }

  boolean overlaps(long resource, LockRequest query) {
    return lowerBeforeUpper(resource, query) && compare(query, false, resource, true) < 0;
  }

  boolean overlaps(long resource, long query) {
    return lowerBeforeUpper(resource, query) && compare(query, false, resource, true) < 0;
  }

  long greaterUpper(long left, long right) {
    return compare(left, true, right, true) >= 0 ? left : right;
  }

  private int compare(long left, boolean leftUpper, long right, boolean rightUpper) {
    boolean leftTuple = tuple(left);
    boolean rightTuple = tuple(right);
    if (leftTuple != rightTuple) return leftTuple ? 1 : -1;
    return leftTuple ? tuples.compare(left, leftUpper, right, rightUpper)
        : compareScalars(left, leftUpper, right, rightUpper);
  }

  private int compare(long left, boolean leftUpper, LockRequest right, boolean rightUpper) {
    boolean leftTuple = tuple(left);
    boolean rightTuple = LockTupleRequest.tuple(right);
    if (leftTuple != rightTuple) return leftTuple ? 1 : -1;
    return leftTuple ? tuples.compare(left, leftUpper, right, rightUpper)
        : compareScalar(left, leftUpper, right, rightUpper);
  }

  private int compare(LockRequest left, boolean leftUpper, long right, boolean rightUpper) {
    return -compare(right, rightUpper, left, leftUpper);
  }

  private int compareScalars(long left, boolean leftUpper, long right, boolean rightUpper) {
    return OrderedKey.compare(
        scalarSpace(left, leftUpper), scalarKey(left, leftUpper),
        scalarSpace(right, rightUpper), scalarKey(right, rightUpper));
  }

  private int compareScalar(long left, boolean leftUpper, LockRequest right, boolean rightUpper) {
    return OrderedKey.compare(
        scalarSpace(left, leftUpper), scalarKey(left, leftUpper),
        scalarSpace(right, rightUpper), scalarKey(right, rightUpper));
  }

  private boolean tuple(long resource) {
    LockExactResourceStore.Chunk chunk = resources.record(resource);
    return LockExactResourceStore.tupleScope(chunk.scopes[LockTypedSlots.offset(resource)]);
  }

  private long scalarSpace(long resource, boolean upper) {
    LockExactResourceStore.Chunk chunk = resources.record(resource);
    int offset = LockTypedSlots.offset(resource);
    if (!upper || chunk.scopes[offset] == LockScope.RANGE.ordinal()) {
      return upper ? chunk.third[offset] : chunk.first[offset];
    }
    return LockIntervalIndex.successorSpace(chunk.first[offset], chunk.second[offset]);
  }

  private long scalarKey(long resource, boolean upper) {
    LockExactResourceStore.Chunk chunk = resources.record(resource);
    int offset = LockTypedSlots.offset(resource);
    if (!upper || chunk.scopes[offset] == LockScope.RANGE.ordinal()) {
      return upper ? chunk.fourth[offset] : chunk.second[offset];
    }
    return LockIntervalIndex.successorKey(chunk.first[offset], chunk.second[offset]);
  }

  private static long scalarSpace(LockRequest request, boolean upper) {
    if (!upper || request.scope() == LockScope.RANGE) {
      return upper ? request.upperSpace() : request.lowerSpace();
    }
    return LockIntervalIndex.successorSpace(request.lowerSpace(), request.lowerKey());
  }

  private static long scalarKey(LockRequest request, boolean upper) {
    if (!upper || request.scope() == LockScope.RANGE) {
      return upper ? request.upperKey() : request.lowerKey();
    }
    return LockIntervalIndex.successorKey(request.lowerSpace(), request.lowerKey());
  }
}
