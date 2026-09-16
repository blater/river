package io.riverdb.engine.table;

/** Intrusive request queue. Mutations use the owning coordinator's monitor. */
final class IndexedCommitRequestQueue {
  private final IndexedGroupCommitBatch batch;
  private final IndexedGroupCommitMetrics metrics;
  private final IndexedDurabilityCohortRing pending;
  private final IndexedTable table;
  private IndexedGroupCommitRequest queueHead;
  private IndexedGroupCommitRequest queueTail;
  private int queued;
  private int queuedGroupable;
  private int selectedDepth;
  private int selectedGroupableDepth;
  private long queueBecameNonemptyNanos;
  private boolean selectedGroupable;
  private boolean selectedCapacityConstrained;

  IndexedCommitRequestQueue(
      IndexedGroupCommitBatch batch, IndexedGroupCommitMetrics metrics,
      IndexedDurabilityCohortRing pending, IndexedTable table) {
    this.batch = batch;
    this.metrics = metrics;
    this.pending = pending;
    this.table = table;
  }

  boolean empty() { return queued == 0; }

  void enqueue(IndexedGroupCommitRequest request) {
    request.next = null;
    if (queueTail == null) {
      queueHead = request;
    } else {
      queueTail.next = request;
    }
    queueTail = request;
    queued++;
    if (request.groupable) queuedGroupable++;
    if (queued == 1) queueBecameNonemptyNanos = System.nanoTime();
    metrics.recordQueueEnqueue(queued);
  }

  void requeueDeferred(int first, int end) {
    int initialDepth = queued;
    for (int index = end - 1; index >= first; index--) {
      IndexedGroupCommitRequest request = batch.takeDeferred(index);
      request.next = queueHead;
      queueHead = request;
      if (queueTail == null) queueTail = request;
      queued++;
      if (request.groupable) queuedGroupable++;
    }
    if (initialDepth == 0 && queued > 0) {
      queueBecameNonemptyNanos = System.nanoTime();
    }
  }

  int drain(int allowed) {
    if (allowed <= 0) return 0;
    int count = 0;
    int depth = queued;
    int groupableDepth = queuedGroupable;
    boolean groupable = queueHead != null && queueHead.groupable;
    int maximum = Math.min(groupable ? batch.capacity() : 1, allowed);
    while (queueHead != null && count < maximum && queueHead.groupable == groupable) {
      IndexedGroupCommitRequest request = queueHead;
      queueHead = request.next;
      request.next = null;
      batch.add(count++, request);
      queued--;
      if (request.groupable) queuedGroupable--;
    }
    if (queueHead == null) queueTail = null;
    if (count > 0) select(depth, groupableDepth, groupable);
    recordEmptyQueue();
    return count;
  }

  private void recordEmptyQueue() {
    if (queued == 0 && queueBecameNonemptyNanos != 0) {
      metrics.recordQueueNonempty(System.nanoTime() - queueBecameNonemptyNanos);
      queueBecameNonemptyNanos = 0;
    }
  }

  private void select(int depth, int groupableDepth, boolean groupable) {
    selectedDepth = depth;
    selectedGroupableDepth = groupableDepth;
    selectedGroupable = groupable;
    selectedCapacityConstrained = groupable && queueHead != null && queueHead.groupable;
  }

  void recordWriterSelection(int selected, int completed) {
    metrics.recordWriterSelection(
        completed,
        selectedDepth,
        selectedGroupableDepth,
        selectedGroupable,
        selectedCapacityConstrained || completed < selected);
  }

  boolean selectableWorkAvailable(boolean pipelineActive) {
    if (queueHead == null) return false;
    if (!queueHead.groupable) return !pipelineActive;
    return pending.canRetain(1) || pending.empty();
  }

  int selectionCapacity() {
    if (queueHead == null) return 0;
    if (!queueHead.groupable) {
      return pending.empty() && !table.forceActive() ? 1 : 0;
    }
    if (pending.canRetain(1)) return pending.remainingMembers();
    return pending.empty() ? 1 : 0;
  }
}
