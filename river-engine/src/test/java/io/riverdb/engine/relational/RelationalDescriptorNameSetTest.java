package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class RelationalDescriptorNameSetTest {
  @Test
  void initialAllocationFailuresPublishNothingAndRetry() {
    for (int failure : new int[] {1, 2}) {
      RelationalDescriptorNameSet names =
          new RelationalDescriptorNameSet(new FailingAllocator(failure));
      assertEquals(StatusCode.RESOURCE_EXHAUSTED, names.reserveInsert());
      assertEquals(0, names.capacity());
      assertEquals(0, names.count());
      assertEquals(StatusCode.OK, names.reserveInsert());
      assertEquals(16, names.capacity());
    }
  }

  @Test
  void growthAllocationFailuresPreservePublishedNamesAndRetry() {
    for (int failure : new int[] {3, 4}) {
      RelationalDescriptorNameSet names =
          new RelationalDescriptorNameSet(new FailingAllocator(failure));
      for (int index = 0; index < 8; index++) {
        assertEquals(StatusCode.OK, names.reserveInsert());
        long hash = index + 101;
        names.insert(names.first(hash), hash, index + 1);
      }
      assertEquals(StatusCode.RESOURCE_EXHAUSTED, names.reserveInsert());
      assertEquals(16, names.capacity());
      assertEquals(8, names.count());
      for (int index = 0; index < 8; index++) {
        assertEquals(index + 1, find(names, index + 101));
      }
      assertEquals(StatusCode.OK, names.reserveInsert());
      assertEquals(32, names.capacity());
      assertEquals(8, names.count());
    }
  }

  private static long find(RelationalDescriptorNameSet names, long hash) {
    int slot = names.first(hash);
    while (names.objectIdAt(slot) != 0) {
      if (names.hashAt(slot) == hash) return names.objectIdAt(slot);
      slot = names.next(slot);
    }
    return 0;
  }

  private static final class FailingAllocator
      implements RelationalDescriptorNameArrayAllocator {
    private final int failure;
    private int allocation;

    FailingAllocator(int failAt) {
      failure = failAt;
    }

    @Override
    public long[] longs(int size) {
      allocation++;
      if (allocation == failure) throw new OutOfMemoryError("injected");
      return new long[size];
    }
  }
}
