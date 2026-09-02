package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlScalarExpression;
import org.junit.jupiter.api.Test;

final class SqlBoundPredicateCapacityTest {
  @Test
  void intOffsetsPreserveProgramsBeyondByteRangeAndBooleanBoundary() {
    SqlBoundBooleanPredicateProgram target = new SqlBoundBooleanPredicateProgram();
    assertEquals(StatusCode.OK,
        SqlBoundPredicateCapacity.reserve(target, 320, 1, 4_096, 0));
    target.beginProgram(0, 0);
    for (int node = 0; node < 320; node++) {
      target.append(0, 0, SqlScalarExpression.LITERAL,
          node, SqlTypeDescriptor.BIGINT, SqlBoundBooleanPredicateProgram.SCOPE_LEFT);
    }
    assertEquals(320, target.nodeCount(0, 0));
    assertEquals(319, target.operand(0, 0, 319));
    target.append(0, 0, SqlScalarExpression.COLUMN, 0,
        SqlTypeDescriptor.BIGINT, SqlNestedRowProvider.scope(0, 63));
    assertEquals(SqlNestedRowProvider.scope(0, 63), target.scope(0, 0, 320));
    assertTrue(target.booleanLeft.length >= 4_096);
  }

  @Test
  void everyAllocationFailureLeavesFamiliesUnpublishedAndRetryable() {
    CountingAllocator counter = new CountingAllocator();
    SqlBoundBooleanPredicateProgram counted = new SqlBoundBooleanPredicateProgram(counter);
    assertEquals(StatusCode.OK,
        SqlBoundPredicateCapacity.reserve(counted, 300, 65, 300, 64));
    for (int failure = 1; failure <= counter.calls; failure++) {
      CountingAllocator allocator = new CountingAllocator();
      allocator.failure = failure;
      SqlBoundBooleanPredicateProgram target = new SqlBoundBooleanPredicateProgram(allocator);
      assertEquals(StatusCode.RESOURCE_EXHAUSTED,
          SqlBoundPredicateCapacity.reserve(target, 300, 65, 300, 64));
      assertEquals(0, target.operators.length);
      assertEquals(0, target.tests.length);
      assertEquals(0, target.booleanOperators.length);
      allocator.failure = 0;
      assertEquals(StatusCode.OK,
          SqlBoundPredicateCapacity.reserve(target, 300, 65, 300, 64));
    }
  }

  private static final class CountingAllocator extends SqlRetainedArrayAllocator {
    private int calls;
    private int failure;

    @Override byte[] bytes(int capacity) { hit(); return super.bytes(capacity); }
    @Override int[] integers(int capacity) { hit(); return super.integers(capacity); }
    @Override long[] longs(int capacity) { hit(); return super.longs(capacity); }
    @Override boolean[] booleans(int capacity) { hit(); return super.booleans(capacity); }
    @Override SqlComparison[] comparisons(int capacity) {
      hit(); return super.comparisons(capacity);
    }

    private void hit() {
      calls++;
      if (calls == failure) throw new OutOfMemoryError("injected");
    }
  }
}
