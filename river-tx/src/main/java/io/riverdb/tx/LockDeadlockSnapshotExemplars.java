package io.riverdb.tx;

/** Exemplar index columns owned by a diagnostic snapshot. */
public final class LockDeadlockSnapshotExemplars {
  private final LockDeadlockSnapshotCounters counters;
  private final int maximumCycleEdges;
  final int[] signatureIndexes;
  final int[] eventIndexes;
  final int[] edgeCounts;

  LockDeadlockSnapshotExemplars(
      int capacity, int cycleEdges, LockDeadlockSnapshotCounters owner) {
    counters = owner;
    maximumCycleEdges = cycleEdges;
    signatureIndexes = new int[capacity];
    eventIndexes = new int[capacity];
    edgeCounts = new int[capacity];
  }

  public int count() { return counters.exemplarCount; }
  public int signatureIndexAt(int index) { return signatureIndexes[checked(index)]; }
  public int eventIndexAt(int index) { return eventIndexes[checked(index)]; }
  public int edgeCountAt(int index) { return edgeCounts[checked(index)]; }
  public int edgeIndex(int exemplar, int edge) {
    int checkedExemplar = checked(exemplar);
    if (edge < 0 || edge >= edgeCounts[checkedExemplar]) {
      throw new IndexOutOfBoundsException(edge);
    }
    return checkedExemplar * maximumCycleEdges + edge;
  }

  void copyFrom(LockDeadlockSnapshotExemplars source) {
    copy(source.signatureIndexes, signatureIndexes);
    copy(source.eventIndexes, eventIndexes);
    copy(source.edgeCounts, edgeCounts);
  }

  private static void copy(int[] source, int[] target) {
    System.arraycopy(source, 0, target, 0, source.length);
  }

  private int checked(int index) {
    if (index < 0 || index >= counters.exemplarCount) {
      throw new IndexOutOfBoundsException(index);
    }
    return index;
  }
}
