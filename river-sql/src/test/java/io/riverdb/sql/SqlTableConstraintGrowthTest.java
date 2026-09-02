package io.riverdb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class SqlTableConstraintGrowthTest {
  @Test
  void definitionGrowthPublishesOnlyAfterEveryAllocationSucceeds() {
    for (int failure = 1; failure <= 5; failure++) {
      FailingAllocator allocator = new FailingAllocator();
      SqlTableConstraintSet constraints = filledDefinitions(allocator);
      allocator.failAt(failure);
      assertEquals(StatusCode.RESOURCE_EXHAUSTED,
          constraints.begin(SqlTableConstraintSet.CHECK));
      assertEquals(4, constraints.count());
      assertEquals("p3", constraints.part(3, 0).toString());
    }
  }

  @Test
  void partGrowthPublishesOnlyAfterBothAllocationsSucceed() {
    for (int failure = 1; failure <= 2; failure++) {
      FailingAllocator allocator = new FailingAllocator();
      SqlTableConstraintSet constraints = new SqlTableConstraintSet(allocator);
      assertEquals(StatusCode.OK, constraints.begin(SqlTableConstraintSet.UNIQUE));
      for (int part = 0; part < 8; part++) {
        assertEquals(StatusCode.OK, constraints.addPart(identifier("p" + part), null));
      }
      allocator.failAt(failure);
      assertEquals(StatusCode.RESOURCE_EXHAUSTED,
          constraints.addPart(identifier("overflow"), null));
      assertEquals(8, constraints.partCount(0));
      assertEquals("p7", constraints.part(0, 7).toString());
    }
  }

  private static SqlTableConstraintSet filledDefinitions(FailingAllocator allocator) {
    SqlTableConstraintSet constraints = new SqlTableConstraintSet(allocator);
    for (int index = 0; index < 4; index++) {
      assertEquals(StatusCode.OK, constraints.begin(SqlTableConstraintSet.CHECK));
      assertEquals(StatusCode.OK, constraints.addPart(identifier("p" + index), null));
    }
    return constraints;
  }

  private static SqlIdentifier identifier(String value) {
    SqlIdentifier result = new SqlIdentifier();
    for (int index = 0; index < value.length(); index++) result.append(value.charAt(index));
    return result;
  }

  private static final class FailingAllocator implements SqlTableConstraintAllocator {
    private int calls;
    private int failure;

    void failAt(int call) { calls = 0; failure = call; }
    private void allocate() { if (++calls == failure) throw new OutOfMemoryError("injected"); }
    public byte[] bytes(int capacity) { allocate(); return new byte[capacity]; }
    public int[] integers(int capacity) { allocate(); return new int[capacity]; }
    public SqlIdentifier[] identifiers(int capacity) {
      allocate();
      return SqlTableConstraintAllocator.STANDARD.identifiers(capacity);
    }
  }
}
