package io.riverdb.engine.table;

/** Caller-owned aggregate snapshot of commit-writer queue behavior. */
public final class IndexedCommitQueueTelemetry {
  private long enqueues;
  private long writerSelections;
  private long selectedTransactions;
  private long depthAtSelection;
  private long groupableDepthAtSelection;
  private long depthOneSelections;
  private long depthMultipleSelections;
  private long groupableDepthOneSelections;
  private long groupableDepthMultipleSelections;
  private long nonemptyNanos;
  private long writerBusyNanos;
  private long coalescingWaits;
  private long coalescingWaitNanos;
  private long capacityConstrainedSelections;
  private int maximumDepth;
  private int maximumGroupableDepth;
  private boolean overflowed;

  public void reset() {
    enqueues = 0;
    writerSelections = 0;
    selectedTransactions = 0;
    depthAtSelection = 0;
    groupableDepthAtSelection = 0;
    depthOneSelections = 0;
    depthMultipleSelections = 0;
    groupableDepthOneSelections = 0;
    groupableDepthMultipleSelections = 0;
    nonemptyNanos = 0;
    writerBusyNanos = 0;
    coalescingWaits = 0;
    coalescingWaitNanos = 0;
    capacityConstrainedSelections = 0;
    maximumDepth = 0;
    maximumGroupableDepth = 0;
    overflowed = false;
  }

  public long enqueues() { return enqueues; }
  public long writerSelections() { return writerSelections; }
  public long selectedTransactions() { return selectedTransactions; }
  public long depthAtSelection() { return depthAtSelection; }
  public long groupableDepthAtSelection() { return groupableDepthAtSelection; }
  public long depthOneSelections() { return depthOneSelections; }
  public long depthMultipleSelections() { return depthMultipleSelections; }
  public long groupableDepthOneSelections() { return groupableDepthOneSelections; }
  public long groupableDepthMultipleSelections() { return groupableDepthMultipleSelections; }
  public long nonemptyNanos() { return nonemptyNanos; }
  public long writerBusyNanos() { return writerBusyNanos; }
  public long coalescingWaits() { return coalescingWaits; }
  public long coalescingWaitNanos() { return coalescingWaitNanos; }
  public long capacityConstrainedSelections() { return capacityConstrainedSelections; }
  public int maximumDepth() { return maximumDepth; }
  public int maximumGroupableDepth() { return maximumGroupableDepth; }
  public boolean overflowed() { return overflowed; }

  boolean reconciles(
      long admittedTransactions,
      long attemptedGroupCohorts,
      long initiallyIneligibleAdmissions) {
    return !overflowed
        && enqueues == admittedTransactions
        && writerSelections == sum(attemptedGroupCohorts, initiallyIneligibleAdmissions)
        && selectedTransactions == enqueues
        && writerSelections == sum(depthOneSelections, depthMultipleSelections)
        && attemptedGroupCohorts == sum(
            groupableDepthOneSelections, groupableDepthMultipleSelections);
  }

  void copyFrom(IndexedCommitQueueTelemetry source) {
    enqueues = source.enqueues;
    writerSelections = source.writerSelections;
    selectedTransactions = source.selectedTransactions;
    depthAtSelection = source.depthAtSelection;
    groupableDepthAtSelection = source.groupableDepthAtSelection;
    depthOneSelections = source.depthOneSelections;
    depthMultipleSelections = source.depthMultipleSelections;
    groupableDepthOneSelections = source.groupableDepthOneSelections;
    groupableDepthMultipleSelections = source.groupableDepthMultipleSelections;
    nonemptyNanos = source.nonemptyNanos;
    writerBusyNanos = source.writerBusyNanos;
    coalescingWaits = source.coalescingWaits;
    coalescingWaitNanos = source.coalescingWaitNanos;
    capacityConstrainedSelections = source.capacityConstrainedSelections;
    maximumDepth = source.maximumDepth;
    maximumGroupableDepth = source.maximumGroupableDepth;
    overflowed = source.overflowed;
  }

  void recordEnqueue(int depth) {
    enqueues = increment(enqueues);
    if (depth > maximumDepth) maximumDepth = depth;
  }

  void recordWriterSelection(
      int count,
      int depth,
      int groupableDepth,
      boolean groupableSelection,
      boolean capacityConstrained) {
    writerSelections = increment(writerSelections);
    selectedTransactions = add(selectedTransactions, count);
    depthAtSelection = add(depthAtSelection, depth);
    groupableDepthAtSelection = add(groupableDepthAtSelection, groupableDepth);
    if (depth == 1) {
      depthOneSelections = increment(depthOneSelections);
    } else if (depth > 1) {
      depthMultipleSelections = increment(depthMultipleSelections);
    }
    if (groupableSelection && groupableDepth == 1) {
      groupableDepthOneSelections = increment(groupableDepthOneSelections);
    } else if (groupableSelection && groupableDepth > 1) {
      groupableDepthMultipleSelections = increment(groupableDepthMultipleSelections);
    }
    if (groupableDepth > maximumGroupableDepth) maximumGroupableDepth = groupableDepth;
    if (capacityConstrained) {
      capacityConstrainedSelections = increment(capacityConstrainedSelections);
    }
  }

  void recordNonempty(long elapsedNanos) {
    nonemptyNanos = add(nonemptyNanos, Math.max(0, elapsedNanos));
  }

  void recordWriterBusy(long elapsedNanos) {
    writerBusyNanos = add(writerBusyNanos, Math.max(0, elapsedNanos));
  }

  void recordCoalescingWait(long elapsedNanos) {
    coalescingWaits = increment(coalescingWaits);
    coalescingWaitNanos = add(coalescingWaitNanos, Math.max(0, elapsedNanos));
  }

  private long increment(long value) {
    if (value == Long.MAX_VALUE) {
      overflowed = true;
      return value;
    }
    return value + 1;
  }

  private long add(long current, long value) {
    if (value < 0 || Long.MAX_VALUE - current < value) {
      overflowed = true;
      return Long.MAX_VALUE;
    }
    return current + value;
  }

  private static long sum(long left, long right) {
    return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
  }
}
