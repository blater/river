package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class SqlDescriptorArrayGrowthTest {
  @Test
  void everyAllocationFailureLeavesSameSizeRetryAdmissible() {
    verify(3, allocator -> new SqlDescriptorTableBuilder(allocator)::reserve);
    verify(7, allocator -> new SqlDescriptorCorrelatedBindings(allocator)::reserve);
    verify(3, allocator -> new SqlDescriptorPredicate(allocator)::reserve);
    verify(2, allocator -> new SqlDescriptorProjection(allocator)::reserveOrder);
    verify(6, allocator -> new SqlDescriptorSetShape(allocator)::reserve);
  }

  private static void verify(int allocations, GrowthFactory factory) {
    for (int failure = 1; failure <= allocations; failure++) {
      FailingAllocator allocator = new FailingAllocator(failure);
      Growth growth = factory.create(allocator);
      assertEquals(StatusCode.RESOURCE_EXHAUSTED, growth.reserve(9));
      allocator.failAt = 0;
      assertEquals(StatusCode.OK, growth.reserve(9));
      int calls = allocator.calls;
      assertEquals(StatusCode.OK, growth.reserve(9));
      assertEquals(calls, allocator.calls);
    }
  }

  @FunctionalInterface
  private interface Growth {
    StatusCode reserve(int size);
  }

  @FunctionalInterface
  private interface GrowthFactory {
    Growth create(SqlRetainedArrayAllocator allocator);
  }

  private static final class FailingAllocator extends SqlRetainedArrayAllocator {
    private int failAt;
    private int calls;

    FailingAllocator(int failure) { failAt = failure; }

    @Override byte[] bytes(int capacity) { fail(); return super.bytes(capacity); }
    @Override int[] integers(int capacity) { fail(); return super.integers(capacity); }
    @Override long[] longs(int capacity) { fail(); return super.longs(capacity); }
    @Override boolean[] booleans(int capacity) { fail(); return super.booleans(capacity); }
    @Override CharSequence[] names(int capacity) { fail(); return super.names(capacity); }
    @Override io.riverdb.sql.SqlComparison[] comparisons(int capacity) {
      fail();
      return super.comparisons(capacity);
    }

    private void fail() {
      if (++calls == failAt) throw new OutOfMemoryError("injected");
    }
  }
}
