package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;

/** Caller-owned immutable view of logical-row floors loaded from one checkpoint generation. */
public final class CheckpointLogicalRowIdDirectory implements CheckpointLogicalRowIdSource {
  private static final long[] EMPTY = new long[0];
  private long[] objectIds = EMPTY;
  private long[] nextExclusive = EMPTY;
  private long[] loadingObjectIds;
  private long[] loadingNextExclusive;
  private int count;
  private int cursor;

  @Override
  public int floorCount() {
    return count;
  }

  @Override
  public void rewind() {
    cursor = 0;
  }

  @Override
  public long nextObjectId() {
    return cursor < count ? objectIds[cursor++] : -1;
  }

  @Override
  public long nextExclusive() {
    return cursor == 0 || cursor > count ? -1 : nextExclusive[cursor - 1];
  }

  /** Returns the published next-exclusive floor, or {@code 1} for an absent object. */
  public long publishedFloor(long objectId) {
    int low = 0;
    int high = count - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      long candidate = objectIds[middle];
      if (candidate < objectId) low = middle + 1;
      else if (candidate > objectId) high = middle - 1;
      else return nextExclusive[middle];
    }
    return 1;
  }

  void reset() {
    objectIds = EMPTY;
    nextExclusive = EMPTY;
    loadingObjectIds = null;
    loadingNextExclusive = null;
    count = 0;
    cursor = 0;
  }

  StatusCode beginLoad(int entries) {
    discardLoad();
    try {
      loadingObjectIds = entries == 0 ? EMPTY : new long[entries];
      loadingNextExclusive = entries == 0 ? EMPTY : new long[entries];
      return StatusCode.OK;
    } catch (OutOfMemoryError exhausted) {
      discardLoad();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void load(int index, long objectId, long floor) {
    loadingObjectIds[index] = objectId;
    loadingNextExclusive[index] = floor;
  }

  void publishLoad(int entries) {
    objectIds = loadingObjectIds;
    nextExclusive = loadingNextExclusive;
    loadingObjectIds = null;
    loadingNextExclusive = null;
    count = entries;
    cursor = 0;
  }

  void discardLoad() {
    loadingObjectIds = null;
    loadingNextExclusive = null;
  }
}
