package io.riverdb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class SqlJoinAdmissionTest {
  @Test
  void commandAndStageAdmissionRetryWithoutPartialPublication() {
    FailingAllocator allocator = new FailingAllocator();
    SqlParser parser = new SqlParser();
    SqlCommand command = new SqlCommand(allocator);
    allocator.failChain = true;
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, parser.parse(join(2), command));
    allocator.failChain = false;
    assertEquals(StatusCode.OK, parser.parse(join(2), command));
    allocator.failPredicate = true;
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, parser.parse(join(3), command));
    allocator.failPredicate = false;
    assertEquals(StatusCode.OK, parser.parse(join(3), command));
    allocator.failInteger = true;
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, parser.parse(join(9), command));
    allocator.failInteger = false;
    assertEquals(StatusCode.OK, parser.parse(join(9), command));
    assertEquals(9, command.joinChain().roleCount());
  }

  private static String join(int roles) {
    StringBuilder sql = new StringBuilder("SELECT t0.id FROM t0");
    for (int role = 1; role < roles; role++) {
      sql.append(" JOIN t").append(role).append(" ON t0.id=t")
          .append(role).append(".id");
    }
    return sql.toString();
  }

  private static final class FailingAllocator extends SqlJoinAllocator {
    private boolean failChain;
    private boolean failPredicate;
    private boolean failInteger;

    @Override SqlJoinChain chain() {
      if (failChain) throw new OutOfMemoryError("chain");
      return super.chain();
    }

    @Override SqlBooleanPredicateProgram predicate() {
      if (failPredicate) throw new OutOfMemoryError("predicate");
      return super.predicate();
    }

    @Override int[] integers(int capacity) {
      if (failInteger) throw new OutOfMemoryError("integers");
      return super.integers(capacity);
    }
  }
}
