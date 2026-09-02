package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;

/** Byte-governed intrusive AVL index shared by scalar and tuple predicate resources. */
final class LockIntervalIndex {
  private final LockIntervalStorage nodes;
  private final LockIntervalMutations mutations;
  private final LockIntervalSearch search;
  private final LockIntervalCursor direct = new LockIntervalCursor();

  LockIntervalIndex(LockExactResourceStore resources, LockSegmentArena arena) {
    nodes = new LockIntervalStorage(resources, arena);
    mutations = new LockIntervalMutations(nodes);
    search = new LockIntervalSearch(nodes);
  }

  StatusCode reserve(long slot) { return nodes.reserve(slot, null); }
  StatusCode reserve(long slot, LockRequest request) { return nodes.reserve(slot, request); }
  void rollbackReservation() { nodes.rollbackReservation(); }
  void commitReservation() { nodes.commitReservation(); }
  void add(long slot) { mutations.add(slot); }
  void remove(long slot) { mutations.remove(slot); }

  StatusCode overlaps(LockRequest request, LockIntervalCursor cursor) {
    if (cursor == null || !valid(request)) return StatusCode.INVALID_EXTERNAL_INPUT;
    cursor.reset(this, request);
    return StatusCode.OK;
  }

  void overlaps(long resource, LockIntervalCursor cursor) { cursor.reset(this, resource); }

  long firstOverlap(long resource) {
    direct.reset(this, resource);
    return search.first(root(), direct, null);
  }

  long nextOverlap(long resource, long current) {
    direct.reset(this, resource);
    return search.next(current, direct, null);
  }

  long firstOverlap(long subtree, LockIntervalCursor query) {
    return search.first(subtree, query, query);
  }

  long nextOverlap(long current, LockIntervalCursor query) {
    return search.next(current, query, query);
  }

  long root() { return nodes.root; }

  static boolean valid(LockRequest request) {
    if (request == null || request.scope() == null
        || !intervalScope((byte) request.scope().ordinal())) return false;
    if (LockTupleRequest.tuple(request)) {
      return LockTupleRequest.valid(request) && LockTupleEndpointOrder.valid(request);
    }
    return LockResourceOverlap.isValid(request.scope(), request.lowerSpace(), request.lowerKey(),
        request.upperSpace(), request.upperKey());
  }

  static boolean intervalScope(byte scope) {
    return scope == LockScope.KEY.ordinal() || scope == LockScope.RANGE.ordinal()
        || scope == LockScope.TUPLE_KEY.ordinal() || scope == LockScope.TUPLE_RANGE.ordinal();
  }

  static long successorSpace(long space, long key) {
    if (key != Long.MAX_VALUE) return space;
    return space == Long.MAX_VALUE ? OrderedKey.INFINITY_SPACE : space + 1;
  }

  static long successorKey(long space, long key) {
    if (key != Long.MAX_VALUE) return key + 1;
    return space == Long.MAX_VALUE ? 0 : Long.MIN_VALUE;
  }
}
