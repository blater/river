package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockRequest;

/** Primitive interval-node fields and their reversible byte admission. */
final class LockIntervalStorage {
  private final LockExactResourceStore resources;
  private final LockIntervalOrder order;
  private final LockLongStore parents;
  private final LockLongStore lefts;
  private final LockLongStore rights;
  private final LockLongStore heights;
  private final LockLongStore maximumResources;
  private final LockIntervalReservation reservation;
  long root = -1;

  LockIntervalStorage(LockExactResourceStore resourceStore, LockSegmentArena arena) {
    resources = resourceStore;
    order = new LockIntervalOrder(resourceStore);
    parents = new LockLongStore(arena);
    lefts = new LockLongStore(arena);
    rights = new LockLongStore(arena);
    heights = new LockLongStore(arena);
    maximumResources = new LockLongStore(arena);
    reservation = new LockIntervalReservation(
        parents, lefts, rights, heights, maximumResources);
  }

  StatusCode reserve(long slot, LockRequest request) {
    if (request == null ? !indexable(slot) : !LockIntervalIndex.valid(request)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return reservation.reserve(slot);
  }

  void rollbackReservation() { reservation.rollback(); }

  void commitReservation() { reservation.commit(); }

  void initialize(long slot) {
    commitReservation();
    parent(slot, -1);
    left(slot, -1);
    right(slot, -1);
    heights.set(slot, 1);
    maximumResources.set(slot, LockTypedSlots.encode(slot));
  }

  void clear(long slot) {
    parents.set(slot, 0);
    lefts.set(slot, 0);
    rights.set(slot, 0);
    heights.set(slot, 0);
    maximumResources.set(slot, 0);
  }

  void update(long slot) {
    heights.set(slot, 1 + Math.max(height(left(slot)), height(right(slot))));
    long maximum = slot;
    long child = left(slot);
    if (child >= 0) maximum = order.greaterUpper(
        maximum, LockTypedSlots.decode(maximumResources.get(child)));
    child = right(slot);
    if (child >= 0) maximum = order.greaterUpper(
        maximum, LockTypedSlots.decode(maximumResources.get(child)));
    maximumResources.set(slot, LockTypedSlots.encode(maximum));
  }

  int compare(long left, long right) {
    return order.compare(left, right);
  }

  boolean maximumAfter(long slot, LockIntervalCursor query) {
    if (slot < 0) return false;
    long maximum = LockTypedSlots.decode(maximumResources.get(slot));
    return query.hasRequest() ? order.maximumAfter(maximum, query.request())
        : order.maximumAfter(maximum, query.resource());
  }

  boolean lowerBeforeUpper(long slot, LockIntervalCursor query) {
    return query.hasRequest() ? order.lowerBeforeUpper(slot, query.request())
        : order.lowerBeforeUpper(slot, query.resource());
  }

  boolean overlaps(long slot, LockIntervalCursor query) {
    return query.hasRequest() ? order.overlaps(slot, query.request())
        : order.overlaps(slot, query.resource());
  }

  int height(long slot) { return slot < 0 ? 0 : (int) heights.get(slot); }
  long parent(long slot) { return LockTypedSlots.decode(parents.get(slot)); }
  long left(long slot) { return LockTypedSlots.decode(lefts.get(slot)); }
  long right(long slot) { return LockTypedSlots.decode(rights.get(slot)); }
  void parent(long slot, long value) {
    if (slot >= 0) parents.set(slot, LockTypedSlots.encode(value));
  }
  void left(long slot, long value) { lefts.set(slot, LockTypedSlots.encode(value)); }
  void right(long slot, long value) { rights.set(slot, LockTypedSlots.encode(value)); }

  private boolean indexable(long slot) {
    if (slot < 0 || !resources.occupied(slot)) return false;
    LockExactResourceStore.Chunk chunk = resources.record(slot);
    int offset = LockTypedSlots.offset(slot);
    return LockIntervalIndex.intervalScope(chunk.scopes[offset]);
  }

}

/** Reversible five-segment admission for one intrusive interval node. */
final class LockIntervalReservation {
  private final LockLongStore parents;
  private final LockLongStore lefts;
  private final LockLongStore rights;
  private final LockLongStore heights;
  private final LockLongStore maximumResources;
  private long slot = -1;
  private int growth;

  LockIntervalReservation(
      LockLongStore parentStore,
      LockLongStore leftStore,
      LockLongStore rightStore,
      LockLongStore heightStore,
      LockLongStore maximumResourceStore) {
    parents = parentStore;
    lefts = leftStore;
    rights = rightStore;
    heights = heightStore;
    maximumResources = maximumResourceStore;
  }

  StatusCode reserve(long requestedSlot) {
    if (slot >= 0) return StatusCode.CONFLICT;
    if (requestedSlot < 0) return StatusCode.RESOURCE_EXHAUSTED;
    slot = requestedSlot;
    growth = 0;
    StatusCode status = reserve(parents, 1);
    if (status.isOk()) status = reserve(lefts, 2);
    if (status.isOk()) status = reserve(rights, 4);
    if (status.isOk()) status = reserve(heights, 8);
    if (status.isOk()) status = reserve(maximumResources, 16);
    if (!status.isOk()) rollback();
    return status;
  }

  void rollback() {
    rollback(maximumResources, 16);
    rollback(heights, 8);
    rollback(rights, 4);
    rollback(lefts, 2);
    rollback(parents, 1);
    commit();
  }

  void commit() {
    growth = 0;
    slot = -1;
  }

  private StatusCode reserve(LockLongStore store, int bit) {
    boolean allocated = store.allocated(slot);
    StatusCode status = store.reserve(slot);
    if (status.isOk() && !allocated) growth |= bit;
    return status;
  }

  private void rollback(LockLongStore store, int bit) {
    if ((growth & bit) != 0) store.rollback(slot);
  }
}
