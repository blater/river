package io.riverdb.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class LockSegmentArenaTest {
  @Test
  void directoryConstructionIsConstantAndHighOrdinalsRemainAddressable() {
    LockSegmentArena arena = new LockSegmentArena(new LockMemoryEnvelope(1L << 20));
    LockLongStore values = new LockLongStore(arena);
    long rootBytes = arena.accountedBytes();
    assertEquals(StatusCode.OK, values.reserve(1L << 48));
    values.set(1L << 48, 73);
    assertEquals(73, values.get(1L << 48));
    assertEquals(StatusCode.OK, values.reserve(Long.MAX_VALUE));
    values.set(Long.MAX_VALUE, 97);
    assertEquals(97, values.get(Long.MAX_VALUE));

    LockSegmentArena larger = new LockSegmentArena(new LockMemoryEnvelope(Long.MAX_VALUE));
    new LockLongStore(larger);
    assertEquals(rootBytes, larger.accountedBytes());
  }

  @Test
  void byteEnvelopeBackpressuresBeforeAllocatingAPath() {
    LockSegmentArena arena = new LockSegmentArena(new LockMemoryEnvelope(1_048));
    LockLongStore values = new LockLongStore(arena);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, values.reserve(0));
    assertEquals(0, values.get(0));
  }

  @Test
  void failedLeafAdmissionRollsBackEveryNewRadixNode() {
    long rootBytes = 1_048;
    long directoryPathBytes = 7 * 2_072L;
    LockSegmentArena arena = new LockSegmentArena(
        new LockMemoryEnvelope(rootBytes + directoryPathBytes));
    LockLongStore values = new LockLongStore(arena);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, values.reserve(1L << 48));
    assertEquals(rootBytes, arena.accountedBytes());
  }

  @Test
  void incrementalHashPreservesCollidingChainsAcrossSplits() {
    LockSegmentArena arena = new LockSegmentArena(new LockMemoryEnvelope(1L << 20));
    LockSlotIndex index = new LockSlotIndex(arena, 91);
    for (long slot = 0; slot < 40; slot++) {
      assertEquals(StatusCode.OK, index.reserve(slot, 7));
      index.add(slot, 7);
    }
    long found = 0;
    for (long slot = index.first(7); slot >= 0; slot = index.next(slot)) {
      long bit = 1L << slot;
      assertEquals(0, found & bit);
      found |= bit;
    }
    assertEquals((1L << 40) - 1, found);
  }

  @Test
  void seededCollisionRemovalAndReaddSurviveIncrementalSplits() {
    LockSegmentArena arena = new LockSegmentArena(new LockMemoryEnvelope(1L << 20));
    LockSlotIndex index = new LockSlotIndex(arena, 0x5eed);
    long firstHash = 11;
    long secondHash = 12;
    while (index.bucketForTest(secondHash) != index.bucketForTest(firstHash)) secondHash++;
    assertEquals(StatusCode.OK, index.reserve(0, firstHash));
    index.add(0, firstHash);
    assertEquals(StatusCode.OK, index.reserve(1, secondHash));
    index.add(1, secondHash);
    for (long slot = 2; slot < 40; slot++) {
      assertEquals(StatusCode.OK, index.reserve(slot, slot * 101));
      index.add(slot, slot * 101);
    }
    index.remove(0, firstHash);
    assertTrue(contains(index, secondHash, 1));
    assertEquals(StatusCode.OK, index.reserve(0, firstHash));
    index.add(0, firstHash);
    assertTrue(contains(index, firstHash, 0));
    assertTrue(contains(index, secondHash, 1));
  }

  @Test
  void splitBucketGrowthFailureRollsBackNewSlotSegments() {
    LockSegmentArena segmentProbe = new LockSegmentArena(
        new LockMemoryEnvelope(Long.MAX_VALUE));
    LockLongStore values = new LockLongStore(segmentProbe);
    assertEquals(StatusCode.OK, values.reserve(0));
    long beforeSecondSegment = segmentProbe.accountedBytes();
    assertEquals(StatusCode.OK, values.reserve(256));
    long subsequentSegmentBytes = segmentProbe.accountedBytes() - beforeSecondSegment;

    LockSegmentArena sizingArena = new LockSegmentArena(
        new LockMemoryEnvelope(Long.MAX_VALUE));
    LockSlotIndex sizingIndex = new LockSlotIndex(sizingArena, 0x51eed);
    populate(sizingIndex, 512);
    long beforeSplit = sizingArena.accountedBytes();

    LockSegmentArena arena = new LockSegmentArena(new LockMemoryEnvelope(
        beforeSplit + 4 * subsequentSegmentBytes - 1));
    LockSlotIndex index = new LockSlotIndex(arena, 0x51eed);
    populate(index, 512);
    assertEquals(beforeSplit, arena.accountedBytes());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, index.reserve(512, hash(512)));
    assertEquals(beforeSplit, arena.accountedBytes());

    index.remove(0, hash(0));
    long replacementHash = 0x7fff_ffffL;
    assertEquals(StatusCode.OK, index.reserve(0, replacementHash));
    index.add(0, replacementHash);
    assertTrue(contains(index, replacementHash, 0));
  }

  private static void populate(LockSlotIndex index, int entries) {
    for (long slot = 0; slot < entries; slot++) {
      long hash = hash(slot);
      assertEquals(StatusCode.OK, index.reserve(slot, hash));
      index.add(slot, hash);
    }
  }

  private static long hash(long slot) { return slot * 101 + 7; }

  private static boolean contains(LockSlotIndex index, long hash, long expected) {
    for (long slot = index.first(hash); slot >= 0; slot = index.next(slot)) {
      if (slot == expected) return true;
    }
    return false;
  }

  @Test
  void typedStoreRejectsConcurrentReservationAndRollbackRestoresFreeTopology() {
    LockSegmentArena arena = new LockSegmentArena(new LockMemoryEnvelope(1L << 20));
    LockExactResourceStore store = new LockExactResourceStore(arena);
    LockSlotReservation initial = new LockSlotReservation();
    assertEquals(StatusCode.OK, store.reserve(initial));
    long reused = initial.slot;
    long firstGeneration = store.generation(reused);
    store.commit(initial);
    store.free(reused);

    LockSlotReservation first = new LockSlotReservation();
    LockSlotReservation second = new LockSlotReservation();
    assertEquals(StatusCode.OK, store.reserve(first));
    assertEquals(StatusCode.CONFLICT, store.reserve(second));
    store.rollback(first);

    LockSlotReservation retried = new LockSlotReservation();
    assertEquals(StatusCode.OK, store.reserve(retried));
    assertEquals(reused, retried.slot);
    assertEquals(firstGeneration + 1, store.generation(retried.slot));
  }

  @Test
  void typedTailGrowthRollbackRestoresAccountedBytes() {
    LockSegmentArena arena = new LockSegmentArena(new LockMemoryEnvelope(1L << 20));
    LockExactRequestStore store = new LockExactRequestStore(arena);
    long before = arena.accountedBytes();
    LockSlotReservation reservation = new LockSlotReservation();
    assertEquals(StatusCode.OK, store.reserve(reservation));
    store.rollback(reservation);
    assertEquals(before, arena.accountedBytes());
  }
}
