package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import org.junit.jupiter.api.Test;

final class SqlAnalyzeWorkspaceTest {
  @Test
  void everyArrayFailurePreservesSharedCapacityForSameSizeRetry() {
    for (int failure = 1; failure <= 7; failure++) {
      FailingAllocator allocator = new FailingAllocator();
      allocator.failure = failure;
      SqlAnalyzeWorkspace workspace = new SqlAnalyzeWorkspace(allocator);
      assertEquals(StatusCode.RESOURCE_EXHAUSTED, workspace.reserve(65));
      assertEquals(0, workspace.distinctCounts.length);
      assertEquals(0, workspace.distinctValues.length);
      allocator.failure = 0;
      assertEquals(StatusCode.OK, workspace.reserve(65));
      assertTrue(workspace.distinctCounts.length >= 65);
    }
  }

  @Test
  void maximumShapeRetainsAtMostOneMebibyteOfExactSamples() {
    SqlAnalyzeWorkspace workspace = new SqlAnalyzeWorkspace();
    assertEquals(StatusCode.OK, workspace.reserve(SqlShapeLimits.MAX_TABLE_COLUMNS));
    assertTrue(workspace.retainedBytes() < 1_100_000L);
  }

  private static final class FailingAllocator extends SqlRetainedArrayAllocator {
    private int calls;
    private int failure;

    @Override long[] longs(int capacity) { hit(); return super.longs(capacity); }
    @Override short[] shorts(int capacity) { hit(); return super.shorts(capacity); }
    @Override boolean[] booleans(int capacity) { hit(); return super.booleans(capacity); }

    private void hit() {
      calls++;
      if (calls == failure) throw new OutOfMemoryError("injected");
    }
  }
}
