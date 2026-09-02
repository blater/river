package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class RelationalPreparedDescriptorEntriesTest {
  @Test
  void initialEntryAndArrayFailuresDoNotPublishCapacity() {
    for (int failure : new int[] {1, 2}) {
      RelationalPreparedDescriptorEntries entries =
          new RelationalPreparedDescriptorEntries(new FailingAllocator(failure));
      assertEquals(StatusCode.RESOURCE_EXHAUSTED, entries.reserve(0));
      assertEquals(0, entries.capacity());
      assertNull(entries.at(0));
      assertEquals(StatusCode.OK, entries.reserve(0));
      assertEquals(4, entries.capacity());
      assertNotNull(entries.at(0));
    }
  }

  @Test
  void geometricGrowthFailuresPreserveTheExistingHighWater() {
    for (int failure : new int[] {6, 7}) {
      RelationalPreparedDescriptorEntries entries =
          new RelationalPreparedDescriptorEntries(new FailingAllocator(failure));
      for (int index = 0; index < 4; index++) {
        assertEquals(StatusCode.OK, entries.reserve(index));
      }
      assertEquals(StatusCode.RESOURCE_EXHAUSTED, entries.reserve(4));
      assertEquals(4, entries.capacity());
      assertNull(entries.at(4));
      assertEquals(StatusCode.OK, entries.reserve(4));
      assertEquals(8, entries.capacity());
      assertNotNull(entries.at(4));
    }
  }

  private static final class FailingAllocator implements RelationalPreparedDescriptorAllocator {
    private final int failure;
    private int allocation;

    FailingAllocator(int failAt) { failure = failAt; }

    @Override
    public RelationalPreparedDescriptorEntry entry() {
      fail();
      return new RelationalPreparedDescriptorEntry();
    }

    @Override
    public RelationalPreparedDescriptorEntry[] entries(int size) {
      fail();
      return new RelationalPreparedDescriptorEntry[size];
    }

    private void fail() {
      allocation++;
      if (allocation == failure) throw new OutOfMemoryError("injected");
    }
  }
}
