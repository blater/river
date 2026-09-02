package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.type.SqlTypeDescriptor;
import org.junit.jupiter.api.Test;

final class SqlBlockSortShapeTest {
  @Test
  void failedGrowthDoesNotPublishPartialArraysOrCount() {
    FailingAllocator allocator = new FailingAllocator();
    SqlBlockSortShape shape = new SqlBlockSortShape(
        new SqlSessionShapeBudget(null), allocator);
    SqlBlockSchema schema = new SqlBlockSchema();
    schema.set(2);
    schema.setColumn(0, "a", SqlTypeDescriptor.BIGINT, false);
    schema.setColumn(1, "b", SqlTypeDescriptor.INTEGER, false);
    allocator.failAt = 2;
    assertFalse(shape.set(schema, new int[] {1, 0}, new boolean[] {true, false}, 2));
    assertEquals(0, shape.count());
    allocator.failAt = 0;
    assertTrue(shape.set(schema, new int[] {1, 0}, new boolean[] {true, false}, 2));
    assertEquals(2, shape.count());
    assertEquals(1, shape.column(0));
    assertEquals(SqlTypeDescriptor.INTEGER, shape.descriptor(0));
    assertTrue(shape.descending(0));
  }

  @Test
  void invalidColumnDoesNotReplacePublishedShape() {
    SqlBlockSortShape shape = new SqlBlockSortShape(new SqlSessionShapeBudget(null));
    SqlBlockSchema schema = new SqlBlockSchema();
    schema.set(1);
    schema.setColumn(0, "a", SqlTypeDescriptor.BIGINT, false);
    assertTrue(shape.set(schema, new int[] {0}, new boolean[] {false}, 1));
    assertFalse(shape.set(schema, new int[] {1}, new boolean[] {true}, 1));
    assertEquals(1, shape.count());
    assertEquals(0, shape.column(0));
    assertFalse(shape.descending(0));
  }

  private static final class FailingAllocator extends SqlRetainedArrayAllocator {
    private int calls;
    private int failAt;

    @Override int[] integers(int capacity) { hit(); return super.integers(capacity); }
    @Override boolean[] booleans(int capacity) { hit(); return super.booleans(capacity); }

    private void hit() {
      calls++;
      if (calls == failAt) throw new OutOfMemoryError("injected");
    }
  }
}
