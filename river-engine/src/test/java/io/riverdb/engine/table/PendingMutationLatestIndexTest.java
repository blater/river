package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

final class PendingMutationLatestIndexTest {
  @Test
  void balancesAscendingDescendingAndExtremeResourcesAcrossChunks() {
    PendingMutationLatestIndex index = new PendingMutationLatestIndex(1_026);
    assertEquals(StatusCode.OK, index.reserve(1_026));

    for (int value = 0; value < 512; value++) index.put(7, value, value);
    for (int value = 1_024; value >= 512; value--) index.put(-3, value, value);
    index.put(Long.MIN_VALUE, Long.MAX_VALUE, 77);

    for (int value = 0; value < 512; value++) assertEquals(value, index.find(7, value));
    for (int value = 512; value <= 1_024; value++) {
      assertEquals(value, index.find(-3, value));
    }
    assertEquals(77, index.find(Long.MIN_VALUE, Long.MAX_VALUE));
    assertEquals(-1, index.find(7, 2_000));
    assertTrue(index.height() <= 16, "AVL height: " + index.height());
  }

  @Test
  void updatesLatestWithoutAddingANodeAndAdvertisesEnterpriseCapacityLazily() {
    PendingMutationLatestIndex index = new PendingMutationLatestIndex(Integer.MAX_VALUE);
    assertEquals(0, index.accountedBytes());
    assertEquals(StatusCode.OK, index.reserve(3));
    long retained = index.accountedBytes();

    index.put(1, 9, 2);
    index.put(1, 9, 7);
    index.put(1, 9, 11);

    assertEquals(11, index.find(1, 9));
    assertEquals(retained, index.accountedBytes());
    assertTrue(index.accountedBytesForEntries(257) > retained);
    assertEquals(-1, index.accountedBytesForEntries(-1));
  }

  @Test
  void prospectiveAccountingMatchesColdGrowthAndFailedAdmissionChangesNothing() {
    PendingMutationLatestIndex index = new PendingMutationLatestIndex(257);
    long predicted = index.accountedBytesForEntries(257);

    assertEquals(StatusCode.OK, index.reserve(257));
    assertEquals(predicted, index.accountedBytes());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, index.reserve(258));
    assertEquals(predicted, index.accountedBytes());

    index.release();
    assertEquals(0, index.accountedBytes());
  }

  @Test
  void retainsPartialColdGrowthAccountingAndResumesAfterPressure() {
    FailingAllocator allocator = new FailingAllocator(2);
    PendingMutationLatestIndex index = new PendingMutationLatestIndex(513, allocator);
    long complete = index.accountedBytesForEntries(513);

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, index.reserve(513));
    assertTrue(index.accountedBytes() > 0);
    assertTrue(index.accountedBytes() < complete);

    allocator.allowAllocations();
    assertEquals(StatusCode.OK, index.reserve(513));
    assertEquals(complete, index.accountedBytes());
  }

  @Test
  void balancesAllRootRotationShapes() {
    assertBalanced(30, 20, 10);
    assertBalanced(10, 20, 30);
    assertBalanced(30, 10, 20);
    assertBalanced(10, 30, 20);
  }

  @Test
  void matchesDeterministicOrderedOracleAcrossChunkBoundary() {
    PendingMutationLatestIndex index = new PendingMutationLatestIndex(600);
    assertEquals(StatusCode.OK, index.reserve(600));
    TreeMap<Resource, Integer> expected = new TreeMap<>((left, right) ->
        OrderedKey.compare(left.space, left.key, right.space, right.key));
    long state = 0x4d595df4d0f33173L;
    for (int mutation = 0; mutation < 600; mutation++) {
      state ^= state << 13;
      state ^= state >>> 7;
      state ^= state << 17;
      long space = state % 19;
      long key = Long.rotateLeft(state, 23) % 401;
      index.put(space, key, mutation);
      expected.put(new Resource(space, key), mutation);
    }
    for (var entry : expected.entrySet()) {
      assertEquals(entry.getValue(), index.find(entry.getKey().space, entry.getKey().key));
    }
    assertTrue(index.height() <= 20, "AVL height: " + index.height());
  }

  private static void assertBalanced(long first, long second, long third) {
    PendingMutationLatestIndex index = new PendingMutationLatestIndex(3);
    assertEquals(StatusCode.OK, index.reserve(3));
    index.put(1, first, 0);
    index.put(1, second, 1);
    index.put(1, third, 2);
    assertEquals(2, index.height());
    assertEquals(0, index.find(1, first));
    assertEquals(1, index.find(1, second));
    assertEquals(2, index.find(1, third));
  }

  private record Resource(long space, long key) { }

  private static final class FailingAllocator
      implements PendingMutationLatestChunkAllocator {
    private final int failedCall;
    private int calls;
    private boolean failing = true;

    private FailingAllocator(int call) { failedCall = call; }

    @Override
    public PendingMutationLatestIndexChunk allocate(int entries) {
      if (failing && ++calls == failedCall) throw new OutOfMemoryError("injected");
      return new PendingMutationLatestIndexChunk(entries);
    }

    private void allowAllocations() { failing = false; }
  }
}
