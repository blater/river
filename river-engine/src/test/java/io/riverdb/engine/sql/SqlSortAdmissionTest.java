package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class SqlSortAdmissionTest {
  @Test
  void everyWideTextAllocationFailsBeforeActivationAndRetries() {
    CountingAllocator counter = new CountingAllocator();
    SqlSortWorkspace counted = new SqlSortWorkspace(counter);
    assertEquals(StatusCode.OK,
        counted.begin(new TableDefinition(), false, 9, true, true, false, 0));
    assertEquals(StatusCode.OK, counted.close());
    for (int failure = 1; failure <= counter.calls; failure++) {
      CountingAllocator allocator = new CountingAllocator();
      allocator.failure = failure;
      SqlSortWorkspace workspace = new SqlSortWorkspace(allocator);
      assertEquals(StatusCode.RESOURCE_EXHAUSTED,
          workspace.begin(new TableDefinition(), false, 9, true, true, false, 0));
      assertFalse(workspace.hasResources());
      allocator.failure = 0;
      assertEquals(StatusCode.OK,
          workspace.begin(new TableDefinition(), false, 9, true, true, false, 0));
      assertEquals(StatusCode.OK, workspace.close());
    }
  }

  @Test
  void closeShedsOversizedProjectionAndGeneratedTextArrays() {
    SqlSortWorkspace workspace = new SqlSortWorkspace();
    assertEquals(
        StatusCode.OK,
        workspace.begin(new TableDefinition(), false, 65, false, true, false, 0));
    assertTrue(workspace.retainedProjectionBytes() > 0);
    assertEquals(StatusCode.OK, workspace.close());
    assertEquals(0, workspace.retainedProjectionBytes());
  }

  private static final class CountingAllocator extends SqlRetainedArrayAllocator {
    private int calls;
    private int failure;

    @Override byte[] bytes(int capacity) { hit(); return super.bytes(capacity); }
    @Override char[] characters(int capacity) {
      hit(); return super.characters(capacity);
    }
    @Override int[] integers(int capacity) { hit(); return super.integers(capacity); }
    @Override long[] longs(int capacity) { hit(); return super.longs(capacity); }
    @Override boolean[] booleans(int capacity) { hit(); return super.booleans(capacity); }
    @Override ByteBuffer direct(int capacity) { hit(); return super.direct(capacity); }

    private void hit() {
      calls++;
      if (calls == failure) throw new OutOfMemoryError("injected");
    }
  }
}
