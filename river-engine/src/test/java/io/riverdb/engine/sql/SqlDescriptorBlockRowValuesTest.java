package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.runtime.RiverRuntimeConfig;
import io.riverdb.engine.runtime.SqlDatabaseRuntime;
import io.riverdb.engine.runtime.SqlRuntimeLease;
import io.riverdb.engine.runtime.SqlRuntimeLeaseResult;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlDescriptorBlockRowValuesTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4445534352495054L, 0x4f52424c4f434b53L);
  private static final int COLUMN_COUNT = 672;
  private static final int REFERENCED_COLUMN = COLUMN_COUNT - 1;

  @Test
  void computedPredicateRetainsOnlyReferencedWideTextLane(@TempDir Path root)
      throws IOException {
    SqlDatabaseRuntime runtime = runtime(root);
    SqlRuntimeLeaseResult leaseResult = new SqlRuntimeLeaseResult();
    assertEquals(StatusCode.OK, runtime.acquire(leaseResult));
    SqlRuntimeLease lease = leaseResult.lease();
    SqlSessionShapeBudget budget = new SqlSessionShapeBudget(lease);
    SqlDescriptorBlockRowValues rows = new SqlDescriptorBlockRowValues(budget);

    assertEquals(StatusCode.OK, rows.prepare(table(), predicate()));
    assertTrue(budget.retainedBytes() < 100_000);
    assertEquals(budget.retainedBytes(), lease.reservedBytes());

    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(COLUMN_COUNT, COLUMN_COUNT, 4, 4));
    assertEquals(StatusCode.OK, values.clearForSize(COLUMN_COUNT));
    assertEquals(StatusCode.OK,
        values.setNull(REFERENCED_COLUMN, SqlTypeDescriptor.varchar(1)));
    assertEquals(StatusCode.OK, rows.load(values));
    assertTrue(rows.row().nullValue(REFERENCED_COLUMN));

    assertEquals(StatusCode.OK, values.clearForSize(COLUMN_COUNT));
    assertEquals(StatusCode.OK,
        values.setText(REFERENCED_COLUMN, SqlTypeDescriptor.varchar(1), "🌊"));
    assertEquals(StatusCode.OK, rows.load(values));
    assertFalse(rows.row().nullValue(REFERENCED_COLUMN));
    assertEquals(2, rows.row().textLength(REFERENCED_COLUMN));
    assertEquals('\uD83C', rows.row().textCharacter(REFERENCED_COLUMN, 0));
    assertEquals('\uDF0A', rows.row().textCharacter(REFERENCED_COLUMN, 1));

    assertEquals(StatusCode.OK, lease.close());
    assertEquals(0, runtime.reservedShapeBytes());
    assertEquals(StatusCode.OK, runtime.prepareClose());
    assertEquals(StatusCode.OK, runtime.completeClose());
  }

  @Test
  void orderedWideNarrowTextScratchRetainsWarmTierAndShedsAboveIt(
      @TempDir Path root) throws IOException {
    SqlDatabaseRuntime runtime = runtime(root);
    SqlRuntimeLeaseResult leaseResult = new SqlRuntimeLeaseResult();
    assertEquals(StatusCode.OK, runtime.acquire(leaseResult));
    SqlRuntimeLease lease = leaseResult.lease();
    SqlSessionShapeBudget budget = new SqlSessionShapeBudget(lease);
    SqlDescriptorOrderedRows ordered = new SqlDescriptorOrderedRows(budget);

    assertEquals(StatusCode.OK, ordered.begin(table(), 0, false));
    long preparedBytes = budget.retainedBytes();
    SqlValueBuffer values = new SqlValueBuffer();
    assertEquals(StatusCode.OK, values.reserve(COLUMN_COUNT, COLUMN_COUNT, 0, 0));
    assertEquals(StatusCode.OK, values.clearForSize(COLUMN_COUNT));
    for (int column = 0; column < COLUMN_COUNT; column++) {
      assertEquals(StatusCode.OK,
          values.setNull(column, SqlTypeDescriptor.varchar(1)));
    }
    assertEquals(StatusCode.OK, ordered.append(values, 1));
    long appendedBytes = budget.retainedBytes();
    assertTrue(appendedBytes > preparedBytes);
    assertEquals(budget.retainedBytes(), lease.reservedBytes());
    assertEquals(StatusCode.OK, ordered.close());
    assertEquals(appendedBytes, budget.retainedBytes());
    assertEquals(budget.retainedBytes(), lease.reservedBytes());

    int[] orderColumns = new int[COLUMN_COUNT];
    boolean[] directions = new boolean[COLUMN_COUNT];
    for (int column = 0; column < COLUMN_COUNT; column++) orderColumns[column] = column;
    assertEquals(
        StatusCode.OK,
        ordered.begin(table(), orderColumns, directions, COLUMN_COUNT));
    for (int row = 0; row < 128; row++) {
      assertEquals(StatusCode.OK, ordered.append(values, row + 1));
    }
    long highWaterBytes = budget.retainedBytes();
    assertTrue(highWaterBytes > appendedBytes);
    assertEquals(StatusCode.OK, ordered.close());
    assertTrue(budget.retainedBytes() < highWaterBytes);
    assertEquals(budget.retainedBytes(), lease.reservedBytes());

    assertEquals(StatusCode.OK, lease.close());
    assertEquals(0, runtime.reservedShapeBytes());
    assertEquals(StatusCode.OK, runtime.prepareClose());
    assertEquals(StatusCode.OK, runtime.completeClose());
  }

  @Test
  void preparedIndexAccessRejectsAChangedCatalogGeneration() {
    TableDescriptor original = indexedTable(1);
    TableDescriptor successor = indexedTable(2);
    SqlUniversalDescriptorIndexAccess access = new SqlUniversalDescriptorIndexAccess();
    access.prepare(
        new SqlCommand(), original, 0, null,
        new SqlBoundBooleanPredicateProgram());

    assertTrue(access.matches(original));
    assertFalse(access.matches(successor));
  }

  private static SqlBoundBooleanPredicateProgram predicate() {
    SqlBoundBooleanPredicateProgram predicate = new SqlBoundBooleanPredicateProgram();
    assertEquals(StatusCode.OK, SqlBoundPredicateCapacity.reserve(predicate, 1, 1, 0, 0));
    predicate.leafCount = 1;
    predicate.prepareLeafPrograms(0);
    predicate.beginProgram(0, SqlBooleanPredicateProgram.PROGRAM_LEFT);
    predicate.append(
        0,
        SqlBooleanPredicateProgram.PROGRAM_LEFT,
        SqlScalarExpression.COLUMN,
        REFERENCED_COLUMN,
        SqlTypeDescriptor.varchar(1),
        SqlBoundBooleanPredicateProgram.SCOPE_LEFT);
    predicate.finishProgram(
        0,
        SqlBooleanPredicateProgram.PROGRAM_LEFT,
        SqlTypeDescriptor.varchar(1),
        REFERENCED_COLUMN);
    return predicate;
  }

  private static TableDescriptor table() {
    int[] types = new int[COLUMN_COUNT];
    CharSequence[] names = new CharSequence[COLUMN_COUNT];
    boolean[] nullable = new boolean[COLUMN_COUNT];
    for (int column = 0; column < COLUMN_COUNT; column++) {
      types[column] = SqlTypeDescriptor.varchar(1);
      names[column] = "c" + column;
      nullable[column] = true;
    }
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(types, names, nullable, columns));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK,
        TableDescriptor.createForTest(columns.value(), null, null, null, table));
    return table.value();
  }

  private static TableDescriptor indexedTable(long generation) {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(
        StatusCode.OK,
        ColumnDescriptorSet.create(
            new int[] {SqlTypeDescriptor.BIGINT},
            new CharSequence[] {"id"},
            new boolean[] {false},
            columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(
        StatusCode.OK,
        KeyDescriptor.create(
            51, KeyDescriptor.KIND_PRIMARY, true, columns.value(),
            new int[] {0}, 0, primary, null));
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(
        StatusCode.OK,
        TableDescriptor.create(
            41, 7, generation, columns.value(), primary.value(),
            null, null, table, null));
    return table.value();
  }

  private static SqlDatabaseRuntime runtime(Path root) throws IOException {
    RiverRuntimeConfig.Result config = new RiverRuntimeConfig.Result();
    assertEquals(StatusCode.OK,
        RiverRuntimeConfig.load(
            root, 64_000_000L, root.toString(), config, new StatusDetail(256)));
    SqlDatabaseRuntime.OpenResult runtime = new SqlDatabaseRuntime.OpenResult();
    assertEquals(StatusCode.OK, SqlDatabaseRuntime.create(
        config.config(), root, DATABASE, runtime, new StatusDetail(256)));
    return runtime.runtime();
  }
}
