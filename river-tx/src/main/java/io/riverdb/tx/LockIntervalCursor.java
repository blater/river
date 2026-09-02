package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockRequest;

/** Caller-owned stackless traversal over one scalar or tuple interval overlap set. */
final class LockIntervalCursor {
  static final long INITIAL = -2;
  private LockIntervalIndex index;
  private LockRequest request;
  private long resource = -1;
  private long position = -1;
  private long visits;

  void reset(LockIntervalIndex owner, LockRequest query) {
    index = owner;
    request = query;
    resource = -1;
    position = INITIAL;
    visits = 0;
  }

  void reset(LockIntervalIndex owner, long queryResource) {
    index = owner;
    request = null;
    resource = queryResource;
    position = INITIAL;
    visits = 0;
  }

  long next() {
    if (index == null || position == -1) return -1;
    position = position == INITIAL
        ? index.firstOverlap(index.root(), this) : index.nextOverlap(position, this);
    return position;
  }

  boolean hasRequest() { return request != null; }
  LockRequest request() { return request; }
  long resource() { return resource; }
  long position() { return position; }
  long visits() { return visits; }
  void visited() { visits++; }
}
