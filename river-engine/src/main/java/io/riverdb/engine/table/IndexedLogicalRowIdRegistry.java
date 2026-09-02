package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.checkpoint.CheckpointLogicalRowIdSource;
import io.riverdb.format.catalog.CatalogKeyspace;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Database-owned, lock-free logical-row-ID authority indexed by table object ID. */
final class IndexedLogicalRowIdRegistry implements CheckpointLogicalRowIdSource {
  private static final int LEVEL_BITS = 8;
  private static final int LEVEL_SIZE = 1 << LEVEL_BITS;
  private static final int LEVEL_MASK = LEVEL_SIZE - 1;
  private static final int ROOT_SIZE = 1 << 7;
  private static final VarHandle REFERENCES = MethodHandles.arrayElementVarHandle(Object[].class);
  private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

  private final AtomicInteger maximumObjectId = new AtomicInteger();
  private final AtomicInteger floorCount = new AtomicInteger();
  private Object[] reservedRoot = new Object[ROOT_SIZE];
  private Object[] publishedRoot = new Object[ROOT_SIZE];
  private int checkpointCursor = 1;
  private long checkpointFloor;

  StatusCode admit(long objectId, long initialPublishedFloor) {
    if (!CatalogKeyspace.validObjectHead(objectId) || initialPublishedFloor <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int index = (int) objectId - 1;
    long[] reserved;
    long[] published;
    try {
      reserved = leaf(reservedRoot, index, true);
      published = leaf(publishedRoot, index, true);
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int slot = index & LEVEL_MASK;
    raise(reserved, slot, initialPublishedFloor);
    if (LONGS.compareAndSet(published, slot, 0L, initialPublishedFloor)) {
      floorCount.incrementAndGet();
    } else {
      raise(published, slot, initialPublishedFloor);
    }
    raiseMaximumObjectId((int) objectId);
    return StatusCode.OK;
  }

  StatusCode load(long objectId, long initialPublishedFloor) {
    return admit(objectId, initialPublishedFloor);
  }

  StatusCode reserve(
      long objectId, int count, IndexedLogicalRowIdReservation result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!CatalogKeyspace.validObjectHead(objectId) || count <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int index = (int) objectId - 1;
    long[] published = leaf(publishedRoot, index, false);
    long[] reserved = leaf(reservedRoot, index, false);
    int slot = index & LEVEL_MASK;
    if (published == null || reserved == null
        || (long) LONGS.getVolatile(published, slot) == 0) {
      return StatusCode.INVARIANT_BROKEN;
    }
    while (true) {
      long first = (long) LONGS.getVolatile(reserved, slot);
      if (first <= 0) return StatusCode.INVARIANT_BROKEN;
      if (first >= Long.MAX_VALUE || count > Long.MAX_VALUE - first) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      long next = first + count;
      if (LONGS.compareAndSet(reserved, slot, first, next)) {
        result.set(objectId, first, count, next);
        return StatusCode.OK;
      }
    }
  }

  StatusCode publishMax(long objectId, long nextLogicalRowId) {
    StatusCode status = validatePublication(objectId, nextLogicalRowId);
    if (!status.isOk()) return status;
    int index = (int) objectId - 1;
    long[] published = leaf(publishedRoot, index, false);
    int slot = index & LEVEL_MASK;
    raise(published, slot, nextLogicalRowId);
    return StatusCode.OK;
  }

  StatusCode validatePublication(long objectId, long nextLogicalRowId) {
    if (!CatalogKeyspace.validObjectHead(objectId) || nextLogicalRowId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int index = (int) objectId - 1;
    long[] published = leaf(publishedRoot, index, false);
    long[] reserved = leaf(reservedRoot, index, false);
    int slot = index & LEVEL_MASK;
    return published == null || reserved == null
        || (long) LONGS.getVolatile(published, slot) == 0
        || nextLogicalRowId > (long) LONGS.getVolatile(reserved, slot)
            ? StatusCode.INVARIANT_BROKEN : StatusCode.OK;
  }

  long reservedNext(long objectId) {
    return value(reservedRoot, objectId);
  }

  long publishedFloor(long objectId) {
    return value(publishedRoot, objectId);
  }

  int maximumObjectId() {
    return maximumObjectId.get();
  }

  @Override
  public int floorCount() { return floorCount.get(); }

  @Override
  public void rewind() {
    checkpointCursor = 1;
    checkpointFloor = -1;
  }

  @Override
  public long nextObjectId() {
    int maximum = maximumObjectId.get();
    while (checkpointCursor <= maximum) {
      int objectId = checkpointCursor++;
      long floor = publishedFloor(objectId);
      if (floor > 0) {
        checkpointFloor = floor;
        return objectId;
      }
    }
    checkpointFloor = -1;
    return -1;
  }

  @Override
  public long nextExclusive() { return checkpointFloor; }

  void reset() {
    clear(reservedRoot);
    clear(publishedRoot);
    maximumObjectId.set(0);
    floorCount.set(0);
    rewind();
  }

  void release() {
    reservedRoot = null;
    publishedRoot = null;
    maximumObjectId.set(0);
    floorCount.set(0);
    rewind();
  }

  private long value(Object[] root, long objectId) {
    if (!CatalogKeyspace.validObjectHead(objectId)) return 0;
    int index = (int) objectId - 1;
    long[] values = leaf(root, index, false);
    return values == null ? 0 : (long) LONGS.getVolatile(values, index & LEVEL_MASK);
  }

  private static long[] leaf(Object[] root, int index, boolean create) {
    Object[] levelThree = child(root, index >>> 24, create);
    if (levelThree == null) return null;
    Object[] levelTwo = child(levelThree, index >>> 16 & LEVEL_MASK, create);
    if (levelTwo == null) return null;
    Object value = REFERENCES.getAcquire(levelTwo, index >>> 8 & LEVEL_MASK);
    if (value != null || !create) return (long[]) value;
    long[] created = new long[LEVEL_SIZE];
    return REFERENCES.compareAndSet(
        levelTwo, index >>> 8 & LEVEL_MASK, null, created)
            ? created
            : (long[]) REFERENCES.getAcquire(levelTwo, index >>> 8 & LEVEL_MASK);
  }

  private static Object[] child(Object[] owner, int slot, boolean create) {
    Object value = REFERENCES.getAcquire(owner, slot);
    if (value != null || !create) return (Object[]) value;
    Object[] created = new Object[LEVEL_SIZE];
    return REFERENCES.compareAndSet(owner, slot, null, created)
        ? created : (Object[]) REFERENCES.getAcquire(owner, slot);
  }

  private static void raise(long[] values, int slot, long floor) {
    long current = (long) LONGS.getVolatile(values, slot);
    while (current < floor && !LONGS.compareAndSet(values, slot, current, floor)) {
      current = (long) LONGS.getVolatile(values, slot);
    }
  }

  private static void clear(Object[] root) {
    for (Object levelThreeValue : root) {
      if (levelThreeValue == null) continue;
      for (Object levelTwoValue : (Object[]) levelThreeValue) {
        if (levelTwoValue == null) continue;
        for (Object leafValue : (Object[]) levelTwoValue) {
          if (leafValue != null) Arrays.fill((long[]) leafValue, 0);
        }
      }
    }
  }

  private void raiseMaximumObjectId(int objectId) {
    int maximum = maximumObjectId.get();
    while (maximum < objectId
        && !maximumObjectId.compareAndSet(maximum, objectId)) {
      maximum = maximumObjectId.get();
    }
  }
}
