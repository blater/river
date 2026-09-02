package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

final class LockIntervalIndexTest {
  private static final long LARGE_ENVELOPE = 64L << 20;

  @Test
  void nestedDisjointAndTouchingHalfOpenIntervalsReturnEachResourceOnce() {
    Fixture fixture = new Fixture(LARGE_ENVELOPE);
    long outer = fixture.range(1, 0, 1, 100);
    long nested = fixture.range(1, 10, 1, 90);
    long narrow = fixture.range(1, 20, 1, 30);
    long touching = fixture.range(1, 30, 1, 40);
    long disjoint = fixture.range(1, 200, 1, 210);

    assertEquals(bits(outer, nested, narrow), fixture.rangeMatches(1, 25, 1, 30));
    assertEquals(bits(outer, nested, touching), fixture.keyMatches(1, 30));
    assertEquals(bits(disjoint), fixture.rangeMatches(1, 200, 1, 210));
    assertEquals(0, fixture.rangeMatches(1, 100, 1, 200));
  }

  @Test
  void exactKeysNormalizeAcrossSignedKeyAndSpaceRollover() {
    Fixture fixture = new Fixture(LARGE_ENVELOPE);
    long minimum = fixture.key(4, Long.MIN_VALUE);
    long ordinary = fixture.key(4, -1);
    long spaceRollover = fixture.key(4, Long.MAX_VALUE);
    long infinityRollover = fixture.key(Long.MAX_VALUE, Long.MAX_VALUE);
    long equivalentRollover = fixture.range(4, Long.MAX_VALUE, 5, Long.MIN_VALUE);
    long equivalentInfinity = fixture.range(
        Long.MAX_VALUE, Long.MAX_VALUE, OrderedKey.INFINITY_SPACE, 0);

    assertEquals(bits(minimum), fixture.keyMatches(4, Long.MIN_VALUE));
    assertEquals(bits(ordinary), fixture.keyMatches(4, -1));
    assertEquals(bits(spaceRollover, equivalentRollover),
        fixture.keyMatches(4, Long.MAX_VALUE));
    assertEquals(bits(infinityRollover, equivalentInfinity),
        fixture.keyMatches(Long.MAX_VALUE, Long.MAX_VALUE));
    assertEquals(0, fixture.rangeMatches(4, 0, 4, Long.MAX_VALUE));
  }

  @Test
  void rotationsAndAllRemovalShapesPreserveOverlapTraversal() {
    Fixture fixture = new Fixture(LARGE_ENVELOPE);
    long expected = 0;
    long[] slots = new long[31];
    for (int index = 0; index < slots.length; index++) {
      slots[index] = fixture.range(7, index * 3L, 7, index * 3L + 2);
      expected |= bit(slots[index]);
    }
    assertEquals(expected, fixture.rangeMatches(7, 0, 7, 100));
    for (int index = 0; index < slots.length; index += 2) {
      fixture.remove(slots[index]);
      expected &= ~bit(slots[index]);
    }
    assertEquals(expected, fixture.rangeMatches(7, 0, 7, 100));
    for (int index = 1; index < slots.length; index += 2) fixture.remove(slots[index]);
    assertEquals(0, fixture.rangeMatches(7, 0, 7, 100));
  }

  @Test
  void removedResourceSlotCanBeReusedWithDifferentInterval() {
    Fixture fixture = new Fixture(LARGE_ENVELOPE);
    long slot = fixture.range(9, 0, 9, 10);
    fixture.removeAndFree(slot);
    long reused = fixture.range(9, 100, 9, 110);

    assertEquals(slot, reused);
    assertEquals(0, fixture.rangeMatches(9, 0, 9, 10));
    assertEquals(bit(reused), fixture.rangeMatches(9, 100, 9, 110));
  }

  @Test
  void reservationRollsBackEveryNewSegmentAtBytePressureBoundary() {
    Fixture roomy = new Fixture(LARGE_ENVELOPE);
    long slot = roomy.resource(LockScope.KEY, 11, 12, 11, 12);
    long baseline = roomy.arena.accountedBytes();
    assertEquals(StatusCode.OK, roomy.index.reserve(slot));
    long growth = roomy.arena.accountedBytes() - baseline;
    roomy.index.rollbackReservation();
    assertEquals(baseline, roomy.arena.accountedBytes());

    Fixture constrained = new Fixture(baseline + growth - 1);
    long constrainedSlot = constrained.resource(LockScope.KEY, 11, 12, 11, 12);
    assertEquals(baseline, constrained.arena.accountedBytes());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, constrained.index.reserve(constrainedSlot));
    assertEquals(baseline, constrained.arena.accountedBytes());
  }

  @Test
  void nonIntervalScopeIsRejectedWithoutGrowingTheIndex() {
    Fixture fixture = new Fixture(LARGE_ENVELOPE);
    long row = fixture.resource(LockScope.ROW, 0, 1, 0, 2);
    long retained = fixture.arena.accountedBytes();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, fixture.index.reserve(row));
    assertEquals(retained, fixture.arena.accountedBytes());
  }

  @Test
  void requestReservesIndexBeforeReservedResourceIsInitializedOrCommitted() {
    Fixture fixture = new Fixture(LARGE_ENVELOPE);
    LockSlotReservation resource = new LockSlotReservation();
    assertEquals(StatusCode.OK, fixture.resources.reserve(resource));
    long retained = fixture.arena.accountedBytes();
    LockRequest request = new LockRequest().setKey(12, 34, LockMode.EXCLUSIVE, 0);

    assertEquals(StatusCode.OK, fixture.index.reserve(resource.slot, request));
    fixture.index.rollbackReservation();
    assertEquals(retained, fixture.arena.accountedBytes());
    fixture.resources.rollback(resource);
  }

  @Test
  void warmedCursorTraversalAllocatesNoBytes() {
    ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    if (!bean.isThreadAllocatedMemorySupported()) return;
    bean.setThreadAllocatedMemoryEnabled(true);
    Fixture fixture = new Fixture(LARGE_ENVELOPE);
    for (int index = 0; index < 32; index++) fixture.range(13, index, 13, index + 4);
    LockRequest query = new LockRequest().setRange(13, 12, 13, 20, LockMode.SHARED, 0);
    LockIntervalCursor cursor = new LockIntervalCursor();
    for (int iteration = 0; iteration < 10_000; iteration++) fixture.scan(query, cursor);

    long thread = Thread.currentThread().threadId();
    long before = bean.getThreadAllocatedBytes(thread);
    long checksum = 0;
    for (int iteration = 0; iteration < 10_000; iteration++) checksum += fixture.scan(query, cursor);
    long allocated = bean.getThreadAllocatedBytes(thread) - before;
    assertTrue(checksum > 0);
    assertTrue(allocated <= 128,
        "warmed interval traversal allocated per-operation bytes: " + allocated);
  }

  @Test
  void structuralVisitsAreLogarithmicPlusReturnedOverlaps() {
    Fixture fixture = new Fixture(LARGE_ENVELOPE);
    int resources = 4_095;
    for (int value = 0; value < resources; value++) {
      fixture.range(17, value * 2L, 17, value * 2L + 1);
    }
    assertEquals(1, fixture.countRange(17, 4_096, 17, 4_097));
    assertTrue(fixture.cursor.visits() <= 64, "point lookup rescanned the interval tree");

    assertEquals(resources, fixture.countRange(17, 0, 17, resources * 2L));
    assertTrue(fixture.cursor.visits() <= resources * 2L + 64,
        "overlap enumeration performed a fresh root search per result");
  }

  private static long bits(long... slots) {
    long value = 0;
    for (long slot : slots) value |= bit(slot);
    return value;
  }

  private static long bit(long slot) { return 1L << slot; }

  private static final class Fixture {
    final LockSegmentArena arena;
    final LockExactResourceStore resources;
    final LockIntervalIndex index;
    private final LockSlotReservation reservation = new LockSlotReservation();
    private final LockIntervalCursor cursor = new LockIntervalCursor();
    private final LockRequest query = new LockRequest();

    Fixture(long maximumBytes) {
      arena = new LockSegmentArena(new LockMemoryEnvelope(maximumBytes));
      resources = new LockExactResourceStore(arena);
      index = new LockIntervalIndex(resources, arena);
    }

    long key(long space, long key) {
      long slot = resource(LockScope.KEY, space, key, space, key);
      assertEquals(StatusCode.OK, index.reserve(slot));
      index.add(slot);
      return slot;
    }

    long range(long lowerSpace, long lowerKey, long upperSpace, long upperKey) {
      long slot = resource(LockScope.RANGE, lowerSpace, lowerKey, upperSpace, upperKey);
      assertEquals(StatusCode.OK, index.reserve(slot));
      index.add(slot);
      return slot;
    }

    long resource(
        LockScope scope, long lowerSpace, long lowerKey, long upperSpace, long upperKey) {
      assertEquals(StatusCode.OK, resources.reserve(reservation));
      long slot = reservation.slot;
      LockExactResourceStore.Chunk chunk = resources.record(slot);
      int offset = LockTypedSlots.offset(slot);
      chunk.scopes[offset] = (byte) scope.ordinal();
      chunk.first[offset] = lowerSpace;
      chunk.second[offset] = lowerKey;
      chunk.third[offset] = upperSpace;
      chunk.fourth[offset] = upperKey;
      resources.commit(reservation);
      return slot;
    }

    void remove(long slot) { index.remove(slot); }

    void removeAndFree(long slot) {
      index.remove(slot);
      resources.free(slot);
    }

    long keyMatches(long space, long key) {
      query.setKey(space, key, LockMode.SHARED, 0);
      return matches(query);
    }

    long rangeMatches(long lowerSpace, long lowerKey, long upperSpace, long upperKey) {
      query.setRange(lowerSpace, lowerKey, upperSpace, upperKey, LockMode.SHARED, 0);
      return matches(query);
    }

    long matches(LockRequest request) {
      assertEquals(StatusCode.OK, index.overlaps(request, cursor));
      long result = 0;
      for (long slot = cursor.next(); slot >= 0; slot = cursor.next()) {
        assertEquals(0, result & bit(slot), "duplicate interval resource");
        result |= bit(slot);
      }
      return result;
    }

    long countRange(long lowerSpace, long lowerKey, long upperSpace, long upperKey) {
      query.setRange(lowerSpace, lowerKey, upperSpace, upperKey, LockMode.SHARED, 0);
      assertEquals(StatusCode.OK, index.overlaps(query, cursor));
      long count = 0;
      while (cursor.next() >= 0) count++;
      return count;
    }

    long scan(LockRequest request, LockIntervalCursor reusable) {
      if (!index.overlaps(request, reusable).isOk()) return -1;
      long checksum = 0;
      for (long slot = reusable.next(); slot >= 0; slot = reusable.next()) checksum += slot + 1;
      return checksum;
    }
  }
}
