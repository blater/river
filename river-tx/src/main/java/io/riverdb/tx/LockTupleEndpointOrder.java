package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockRequest;

/** Resource-aware unsigned tuple endpoint comparison. */
final class LockTupleEndpointOrder {
  private final LockExactResourceStore resources;

  LockTupleEndpointOrder(LockExactResourceStore store) { resources = store; }

  int compare(long left, boolean leftUpper, long right, boolean rightUpper) {
    LockExactResourceStore.Chunk lc = resources.record(left);
    LockExactResourceStore.Chunk rc = resources.record(right);
    int lo = LockTypedSlots.offset(left);
    int ro = LockTypedSlots.offset(right);
    int namespace = Long.compare(lc.tupleNamespaces[lo], rc.tupleNamespaces[ro]);
    return namespace != 0 ? namespace : LockTupleBytes.compare(
        LockTupleEndpoint.bytes(lc, lo, leftUpper), LockTupleEndpoint.length(lc, lo, leftUpper),
        LockTupleEndpoint.kind(lc, lo, leftUpper),
        LockTupleEndpoint.bytes(rc, ro, rightUpper), LockTupleEndpoint.length(rc, ro, rightUpper),
        LockTupleEndpoint.kind(rc, ro, rightUpper));
  }

  int compare(long left, boolean leftUpper, LockRequest right, boolean rightUpper) {
    LockExactResourceStore.Chunk lc = resources.record(left);
    int lo = LockTypedSlots.offset(left);
    int namespace = Long.compare(lc.tupleNamespaces[lo], right.tupleNamespace());
    return namespace != 0 ? namespace : LockTupleBytes.compare(
        LockTupleEndpoint.bytes(lc, lo, leftUpper), LockTupleEndpoint.length(lc, lo, leftUpper),
        LockTupleEndpoint.kind(lc, lo, leftUpper),
        LockTupleEndpoint.buffer(right, rightUpper), LockTupleEndpoint.offset(right, rightUpper),
        LockTupleEndpoint.length(right, rightUpper), LockTupleEndpoint.kind(right, rightUpper));
  }

  static boolean valid(LockRequest request) {
    return LockTupleBytes.compare(
        LockTupleEndpoint.buffer(request, false), LockTupleEndpoint.offset(request, false),
        LockTupleEndpoint.length(request, false), LockTupleEndpoint.kind(request, false),
        LockTupleEndpoint.buffer(request, true), LockTupleEndpoint.offset(request, true),
        LockTupleEndpoint.length(request, true), LockTupleEndpoint.kind(request, true)) < 0;
  }
}
