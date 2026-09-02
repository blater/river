package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlParser;
import io.riverdb.sql.SqlScalarExpression;
import org.junit.jupiter.api.Test;

final class SqlJoinProjectionEvaluatorTest {
  @Test
  void legacyBlockTextAllocationFailureIsReturnedAndRetryable() {
    Fixture fixture = new Fixture();
    FailingAllocator allocator = new FailingAllocator();
    SqlBlockRow result = new SqlBlockRow(allocator);
    assertEquals(StatusCode.OK, result.reset(1));
    LegacyRows rows = new LegacyRows();
    SqlJoinProjectionEvaluator evaluator = fixture.evaluator();

    allocator.fail = true;
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, evaluator.project(rows, result));
    assertEquals(0, result.count());
    allocator.fail = false;
    assertEquals(StatusCode.OK, evaluator.project(rows, result));
    assertEquals(1_000, result.key());
    assertText(result, "abc");
  }

  @Test
  void universalBlockTextAllocationFailureIsReturnedAndRetryable() {
    Fixture fixture = new Fixture();
    FailingAllocator allocator = new FailingAllocator();
    SqlBlockRow result = new SqlBlockRow(allocator);
    assertEquals(StatusCode.OK, result.reset(1));
    UniversalRows rows = new UniversalRows();
    SqlJoinProjectionEvaluator evaluator = fixture.evaluator();

    allocator.fail = true;
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, evaluator.project(rows, result));
    assertEquals(0, result.count());
    allocator.fail = false;
    assertEquals(StatusCode.OK, evaluator.project(rows, result));
    assertEquals(1_000, result.key());
    assertText(result, "abc");
  }

  private static void assertText(SqlBlockRow row, String expected) {
    assertEquals(expected.length(), row.textLength(0));
    assertEquals(expected, new String(row.text(0), 0, row.textLength(0)));
  }

  private static final class Fixture {
    private final BoundSqlStatement bound = new BoundSqlStatement();
    private final SqlProjectionZoneSet zones = new SqlProjectionZoneSet();

    Fixture() {
      SqlCommand parsed = new SqlCommand();
      assertEquals(StatusCode.OK, new SqlParser().parse(
          "SELECT 'abc' AS label FROM labels", parsed));
      assertEquals(StatusCode.OK, bound.command.copyBlockFrom(parsed));
      SqlScalarExpression expression = parsed.projectionExpression(0);
      int descriptor = expression.resultTypeDescriptor();
      assertEquals(SqlTypeDescriptor.TYPE_ID_VARCHAR,
          SqlTypeDescriptor.typeId(descriptor));
      assertEquals(StatusCode.OK, bound.reserveProjectionColumns(1));
      bound.projectedColumnCount = 1;
      bound.projectedTypeDescriptors[0] = descriptor;
      bound.projectionPrograms.begin(1);
      bound.projectionPrograms.append(
          0, expression.operator(0), expression.operandHigh(0),
          expression.operand(0), descriptor);
      bound.projectionPrograms.finish(0, descriptor, -1);
      assertEquals(StatusCode.OK, zones.reserve(1));
    }

    SqlJoinProjectionEvaluator evaluator() {
      SqlJoinProjectionEvaluator evaluator = new SqlJoinProjectionEvaluator(
          new SqlRowExpressionEvaluator(new SqlExpressionEvaluator(), new SqlTemporalContext()));
      evaluator.bind(bound, zones);
      return evaluator;
    }
  }

  private static final class LegacyRows extends SqlJoinRoleRows {
    @Override long key(int role) { return 1_000; }
    @Override HeapRowResult row(int role) { return null; }
    @Override TableDefinition table(int role) { return null; }
  }

  private static final class UniversalRows extends SqlUniversalJoinRows {
    UniversalRows() { super(null); }
    @Override long key(int role) { return 91; }
    @Override long publicKey(int role) { return 1_000; }
    @Override SqlBlockRow row(int role) { return null; }
    @Override TableDefinition table(int role) { return null; }
  }

  private static final class FailingAllocator extends SqlRetainedArrayAllocator {
    private boolean fail;

    @Override char[] characters(int capacity) {
      if (fail) throw new OutOfMemoryError("injected");
      return super.characters(capacity);
    }
  }
}
