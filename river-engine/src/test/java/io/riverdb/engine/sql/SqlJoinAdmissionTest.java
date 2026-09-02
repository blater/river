package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableStatistics;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class SqlJoinAdmissionTest {
  @Test
  void roleResourcesGrowOnceAtActualCountAndRetainHighWater() {
    CountingJoinAllocator allocator = new CountingJoinAllocator();
    SqlJoinRoleRows rows = new SqlJoinRoleRows(allocator);
    assertEquals(0, allocator.calls);
    assertEquals(StatusCode.OK, rows.prepare(9));
    int nine = allocator.calls;
    assertEquals(StatusCode.OK, rows.prepare(9));
    assertEquals(nine, allocator.calls);
    assertEquals(StatusCode.OK, rows.prepare(64));
    int sixtyFour = allocator.calls;
    assertEquals(StatusCode.OK, rows.prepare(64));
    assertEquals(sixtyFour, allocator.calls);
  }

  @Test
  void outerRowFailureDoesNotPublishAndSameSizeRetrySucceeds() {
    CountingJoinAllocator allocator = new CountingJoinAllocator();
    allocator.failRowBytesAt = 2;
    SqlJoinRoleRows rows = new SqlJoinRoleRows(allocator);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, rows.prepare(3));
    allocator.failRowBytesAt = 0;
    assertEquals(StatusCode.OK, rows.prepare(3));
    int calls = allocator.calls;
    assertEquals(StatusCode.OK, rows.prepare(3));
    assertEquals(calls, allocator.calls);
  }

  @Test
  void boundContextRetriesEveryTransactionalAllocationBoundary() {
    for (int failure = 1; failure <= 18; failure++) {
      FailingContextAllocator allocator = new FailingContextAllocator(failure);
      SqlBoundJoinContext context = new SqlBoundJoinContext(allocator);
      assertEquals(StatusCode.RESOURCE_EXHAUSTED, context.prepare(2));
      allocator.failAt = 0;
      assertEquals(StatusCode.OK, context.prepare(2));
      int calls = allocator.calls;
      assertEquals(StatusCode.OK, context.prepare(2));
      assertEquals(calls, allocator.calls);
    }
  }

  private static final class CountingJoinAllocator extends SqlJoinResourceAllocator {
    private int calls;
    private int rowBytes;
    private int failRowBytesAt;

    @Override SqlJoinOuterRow outerRow() { calls++; return super.outerRow(); }

    @Override ByteBuffer rowBytes(int capacity) {
      calls++;
      if (++rowBytes == failRowBytesAt) throw new OutOfMemoryError("injected");
      return super.rowBytes(capacity);
    }

    @Override long[] longs(int capacity) { calls++; return super.longs(capacity); }
    @Override boolean[] booleans(int capacity) { calls++; return super.booleans(capacity); }
    @Override io.riverdb.storage.heap.HeapRowResult[] heapRows(int capacity) {
      calls++;
      return super.heapRows(capacity);
    }
    @Override SqlJoinOuterRow[] outerRows(int capacity) {
      calls++;
      return super.outerRows(capacity);
    }
  }

  private static final class FailingContextAllocator extends SqlJoinContextAllocator {
    private int failAt;
    private int calls;

    FailingContextAllocator(int failure) { failAt = failure; }
    private void fail() { if (++calls == failAt) throw new OutOfMemoryError("injected"); }
    @Override TableDefinition[] tables(int capacity) { fail(); return super.tables(capacity); }
    @Override TableStatistics[] statistics(int capacity) {
      fail(); return super.statistics(capacity);
    }
    @Override SqlBoundBooleanPredicateProgram[] predicates(int capacity) {
      fail(); return super.predicates(capacity);
    }
    @Override boolean[] booleans(int capacity) { fail(); return super.booleans(capacity); }
    @Override byte[] bytes(int capacity) { fail(); return super.bytes(capacity); }
    @Override int[] integers(int capacity) { fail(); return super.integers(capacity); }
    @Override long[] longs(int capacity) { fail(); return super.longs(capacity); }
    @Override TableDefinition table() { fail(); return super.table(); }
    @Override TableStatistics statistic() { fail(); return super.statistic(); }
    @Override SqlBoundBooleanPredicateProgram predicate() { fail(); return super.predicate(); }
  }
}
