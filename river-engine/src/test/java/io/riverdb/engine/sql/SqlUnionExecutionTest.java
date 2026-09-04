package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlParser;
import io.riverdb.sql.SqlQuery;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlUnionExecutionTest {
  @TempDir private Path root;
  private final SqlParser parser = new SqlParser();
  private final SqlQuery query = new SqlQuery();
  private final SqlCommand command = new SqlCommand();
  private final SqlBlockRow result = new SqlBlockRow();
  private SqlMaterializedTestFixture fixture;
  private SqlUnionExecution union;

  @BeforeEach
  void openMaterializedRuntime() {
    fixture = SqlMaterializedTestFixture.open(root);
    union = new SqlUnionExecution(fixture.budget());
  }

  @AfterEach
  void closeMaterializedRuntime() {
    assertEquals(StatusCode.OK, union.close());
    fixture.close();
  }

  @Test
  void appliesLeafAndRootOrderingBeforeTheirLimits() {
    parse(
        "(SELECT id FROM a ORDER BY id DESC LIMIT 2) "
            + "UNION ALL SELECT id FROM b ORDER BY id LIMIT 3");
    TestLeaves leaves = new TestLeaves(2);
    leaves.set(0, schema("id", SqlTypeDescriptor.BIGINT, false), singleRows(1L, 3L, 2L));
    leaves.set(1, schema("id", SqlTypeDescriptor.BIGINT, false), singleRows(4L, 0L));

    assertEquals(StatusCode.OK, union.run(query, command, leaves));
    assertValues(0, 2, 3);
    assertEquals(StatusCode.OK, union.close());
  }

  @Test
  void unionDistinctUsesNullAwareFullRowEquality() {
    parse("SELECT id,label FROM a UNION SELECT id,label FROM b");
    TestLeaves leaves = new TestLeaves(2);
    SqlBlockSchema schema = schema(
        new String[] {"id", "label"},
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.varchar(16)},
        new boolean[] {true, true});
    leaves.set(0, schema, rows(
        row(1L, "x"), row(null, "z"), row(2L, null)));
    leaves.set(1, schema, rows(
        row(1L, "x"), row(null, "z"), row(2L, null), row(3L, "x")));

    assertEquals(StatusCode.OK, union.run(query, command, leaves));
    assertEquals(4, union.output().rowCount());
    assertRow(null, "z");
    assertRow(1L, "x");
    assertRow(2L, null);
    assertRow(3L, "x");
    assertEquals(StatusCode.OK, union.close());
  }

  @Test
  void preservesDistinctBoundaryBelowUnionAll() {
    parse(
        "(SELECT id FROM a UNION SELECT id FROM b) "
            + "UNION ALL SELECT id FROM c");
    TestLeaves leaves = new TestLeaves(3);
    SqlBlockSchema schema = schema("id", SqlTypeDescriptor.BIGINT, false);
    leaves.set(0, schema, singleRows(1L, 1L));
    leaves.set(1, schema, singleRows(2L, 1L));
    leaves.set(2, schema, singleRows(1L));

    assertEquals(StatusCode.OK, union.run(query, command, leaves));
    assertValues(1, 2, 1);
    assertEquals(5, union.stagePlan().count());
    assertEquals(PackedText.pack("union"), union.stagePlan().operator(2));
    assertEquals(PackedText.pack("unionall"), union.stagePlan().operator(4));
    assertEquals(StatusCode.OK, union.close());
  }

  @Test
  void reconcilesAndCanonicalizesIntegralAndDecimal128Rows() {
    parse("SELECT amount FROM a UNION SELECT amount FROM b");
    TestLeaves leaves = new TestLeaves(2);
    leaves.set(
        0,
        schema("amount", SqlTypeDescriptor.BIGINT, false),
        singleRows(2L));
    leaves.set(
        1,
        schema("amount", SqlTypeDescriptor.decimal(38, 2), true),
        singleRows(new Wide(BigInteger.valueOf(200))));

    assertEquals(StatusCode.OK, union.run(query, command, leaves));
    assertEquals(SqlTypeDescriptor.decimal(38, 2), union.schema().descriptor(0));
    assertTrue(union.schema().nullable(0));
    assertEquals(1, union.output().rowCount());
    assertEquals(StatusCode.OK, union.output().next(result));
    assertEquals(0, result.highValue(0));
    assertEquals(200, result.value(0));
    assertEquals(StatusCode.OK, union.close());
  }

  @Test
  void retainsAdmittedOutputAndRejectsIncompatibleSchemas() {
    parse("SELECT id FROM a UNION ALL SELECT id FROM b");
    TestLeaves leaves = new TestLeaves(2);
    SqlBlockSchema numeric = schema("id", SqlTypeDescriptor.BIGINT, false);
    Object[][] many = new Object[1_500][];
    for (int index = 0; index < many.length; index++) many[index] = row((long) index);
    leaves.set(0, numeric, many);
    leaves.set(1, numeric, new Object[0][]);
    assertEquals(StatusCode.OK, union.run(query, command, leaves));
    assertEquals(1_500, union.output().rowCount());
    assertEquals(StatusCode.OK, union.close());

    parse("SELECT id FROM a UNION SELECT id FROM b");
    leaves.set(0, numeric, singleRows(1L));
    leaves.set(1, schema("id", SqlTypeDescriptor.varchar(8), false), singleRows("1"));
    assertEquals(StatusCode.DATATYPE_MISMATCH, union.run(query, command, leaves));
    assertEquals(StatusCode.OK, union.close());
  }

  @Test
  void canonicalizesApproximateZeroAndUnicodeDistinctRows() {
    parse("SELECT value,label FROM a UNION SELECT value,label FROM b");
    TestLeaves leaves = new TestLeaves(2);
    leaves.set(
        0,
        schema(
            new String[] {"value", "label"},
            new int[] {SqlTypeDescriptor.REAL, SqlTypeDescriptor.varchar(16)},
            new boolean[] {false, false}),
        rows(row(
            Integer.toUnsignedLong(Float.floatToRawIntBits(-0.0f)), "é😀")));
    leaves.set(
        1,
        schema(
            new String[] {"value", "label"},
            new int[] {SqlTypeDescriptor.DOUBLE, SqlTypeDescriptor.varchar(16)},
            new boolean[] {false, false}),
        rows(row(Double.doubleToRawLongBits(0.0d), "é😀")));

    assertEquals(StatusCode.OK, union.run(query, command, leaves));
    assertEquals(1, union.output().rowCount());
    assertEquals(StatusCode.OK, union.close());
  }

  @Test
  void reconcilesDecimalScaleAndIntegerWidthWithoutLoss() {
    parse("SELECT amount FROM a UNION ALL SELECT amount FROM b");
    TestLeaves leaves = new TestLeaves(2);
    leaves.set(
        0,
        schema("amount", SqlTypeDescriptor.decimal(22, 18), false),
        singleRows(new Wide(BigInteger.valueOf(123456789012345678L))));
    leaves.set(
        1,
        schema("amount", SqlTypeDescriptor.decimal(22, 2), false),
        singleRows(12345L));

    assertEquals(StatusCode.OK, union.run(query, command, leaves));
    assertEquals(SqlTypeDescriptor.decimal(38, 18), union.schema().descriptor(0));
    assertEquals(2, union.output().rowCount());
    assertEquals(StatusCode.OK, union.close());
  }

  @Test
  void chargesOrderedLeafStorageToTheSessionBudget() {
    SqlSessionShapeBudget budget = fixture.budget();
    parse("(SELECT id FROM a ORDER BY id DESC) UNION ALL SELECT id FROM b");
    TestLeaves leaves = new TestLeaves(2);
    Object[][] many = new Object[1_500][];
    for (int index = 0; index < many.length; index++) many[index] = row((long) index);
    SqlBlockSchema schema = schema("id", SqlTypeDescriptor.BIGINT, false);
    leaves.set(0, schema, many);
    leaves.set(1, schema, new Object[0][]);

    assertEquals(StatusCode.OK, union.run(query, command, leaves));
    assertTrue(budget.retainedBytes() > 0);
    assertEquals(budget.retainedBytes(), fixture.leaseReservedBytes());
    assertEquals(StatusCode.OK, union.close());
  }

  @Test
  void describesWithoutOpeningOperandRows() {
    parse("EXPLAIN SELECT id FROM a UNION ALL SELECT id FROM b ORDER BY id LIMIT 1");
    TestLeaves leaves = new TestLeaves(2);
    SqlBlockSchema schema = schema("id", SqlTypeDescriptor.BIGINT, false);
    leaves.set(0, schema, singleRows(1L));
    leaves.set(1, schema, singleRows(2L));

    assertEquals(StatusCode.OK, union.describe(query, command, leaves));
    assertEquals(0, leaves.opens());
    assertEquals(5, union.stagePlan().count());
    assertEquals(PackedText.pack("sort"), union.stagePlan().operator(3));
    assertEquals(PackedText.pack("limit"), union.stagePlan().operator(4));
    assertEquals(-1, union.stagePlan().rows(4));
    assertEquals(StatusCode.OK, union.close());
  }

  private void parse(String sql) {
    assertEquals(StatusCode.OK, parser.parseQuery(sql, query, command));
  }

  private void assertValues(long... expected) {
    assertEquals(expected.length, union.output().rowCount());
    for (long value : expected) {
      assertEquals(StatusCode.OK, union.output().next(result));
      assertEquals(value, result.value(0));
    }
    assertEquals(StatusCode.CONFLICT, union.output().next(result));
  }

  private void assertRow(Long id, String label) {
    assertEquals(StatusCode.OK, union.output().next(result));
    assertEquals(id == null, result.nullValue(0));
    if (id != null) assertEquals(id.longValue(), result.value(0));
    assertEquals(label == null, result.nullValue(1));
    if (label != null) {
      assertEquals(label.length(), result.textLength(1));
      for (int index = 0; index < label.length(); index++) {
        assertEquals(label.charAt(index), result.textCharacter(1, index));
      }
    }
  }

  private static SqlBlockSchema schema(String name, int descriptor, boolean nullable) {
    return schema(new String[] {name}, new int[] {descriptor}, new boolean[] {nullable});
  }

  private static SqlBlockSchema schema(
      String[] names, int[] descriptors, boolean[] nullable) {
    SqlBlockSchema schema = new SqlBlockSchema();
    schema.set(names.length);
    for (int column = 0; column < names.length; column++) {
      schema.setColumn(column, names[column], descriptors[column], nullable[column]);
    }
    return schema;
  }

  private static Object[][] singleRows(Object... values) {
    Object[][] rows = new Object[values.length][];
    for (int index = 0; index < values.length; index++) rows[index] = row(values[index]);
    return rows;
  }

  private static Object[][] rows(Object[]... values) { return values; }

  private static Object[] row(Object... values) { return values; }

  private record Wide(long high, long low) {
    Wide(BigInteger value) { this(value.shiftRight(64).longValue(), value.longValue()); }
  }

  private static final class TestLeaves implements SqlUnionLeafSource {
    private final SqlBlockSchema[] schemas;
    private final Object[][][] rows;
    private int block = -1;
    private int row;
    private int opens;

    TestLeaves(int count) {
      schemas = new SqlBlockSchema[count];
      rows = new Object[count][][];
    }

    void set(int index, SqlBlockSchema schema, Object[][] values) {
      schemas[index] = schema;
      rows[index] = values;
    }

    @Override public StatusCode describe(int index, SqlBlockSchema destination) {
      if (index < 0 || index >= schemas.length || schemas[index] == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      destination.copyFrom(schemas[index]);
      return destination.status();
    }

    @Override public StatusCode open(int index) {
      if (index < 0 || index >= rows.length || rows[index] == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      block = index;
      row = 0;
      opens++;
      return StatusCode.OK;
    }

    @Override public SqlBlockSchema schema() {
      return block < 0 ? null : schemas[block];
    }

    @Override public boolean finalized() { return false; }

    int opens() { return opens; }

    @Override public StatusCode next(SqlBlockRow destination) {
      if (block < 0 || row >= rows[block].length) return StatusCode.CONFLICT;
      Object[] values = rows[block][row++];
      StatusCode status = destination.reset(values.length);
      for (int column = 0; status.isOk() && column < values.length; column++) {
        Object value = values[column];
        if (value == null) destination.setNull(column);
        else if (value instanceof Long number) destination.setValue(column, number);
        else if (value instanceof String text) {
          char[] characters = text.toCharArray();
          status = destination.setText(column, characters, 0, characters.length);
        } else if (value instanceof Wide wide) {
          destination.setDecimal128(column, wide.high(), wide.low());
        } else status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      return status;
    }

    @Override public StatusCode close(StatusCode runtimeStatus) {
      block = -1;
      row = 0;
      return runtimeStatus;
    }
  }
}
