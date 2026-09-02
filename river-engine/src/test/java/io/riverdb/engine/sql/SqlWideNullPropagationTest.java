package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlAggregateKind;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlWideNullPropagationTest {
  private static final int COLUMNS = 130;

  @Test
  void inMemorySortAndProjectedResultPreserveThirdNullWord() {
    SqlSortWorkspace workspace = new SqlSortWorkspace();
    SqlProjectedRow projected = projected();
    projected.setNull(64);
    projected.setNull(129);
    assertEquals(StatusCode.OK, workspace.begin(
        new TableDefinition(), false, COLUMNS, false, false, false, 0));
    assertEquals(StatusCode.OK, workspace.append(
        0, 2, false, 2,
        projected.highs(), projected.values(), projected, null, projected));
    assertEquals(StatusCode.OK, workspace.finish());
    workspace.selectNullWordsAt(0);
    assertTrue(workspace.nullAt(64));
    assertTrue(workspace.nullAt(129));
    assertFalse(workspace.nullAt(63));

    SqlScanRowResult result = new SqlScanRowResult();
    assertEquals(StatusCode.OK, result.setWords(
        2, projected.values(), workspace, descriptors(), COLUMNS));
    assertTrue(result.isNull(64));
    assertTrue(result.isNull(129));
    assertEquals(StatusCode.OK, workspace.close());
  }

  @Test
  void spillKeepsColumn63IndependentFromSortKeyNullAndLaterWords(@TempDir Path root) {
    SqlMaterializedTestRuntime runtime = SqlMaterializedTestRuntime.open(root);
    SqlSortWorkspace workspace = runtime.workspace();
    SqlProjectedRow projected = projected();
    assertEquals(StatusCode.OK, workspace.begin(
        new TableDefinition(), false, COLUMNS, false, false, false, 0));
    for (int row = 0; row < 1_025; row++) {
      projected.reset(COLUMNS);
      projected.setValue(0, row);
      if (row == 0) {
        projected.setNull(63);
        projected.setNull(64);
        projected.setNull(129);
      }
      assertEquals(StatusCode.OK, workspace.append(
          0, row, row == 0, row + 1L,
          projected.highs(), projected.values(), projected, null, projected));
    }
    assertEquals(StatusCode.OK, workspace.finish());
    assertTrue(workspace.isSpilled());
    long[] values = new long[COLUMNS];
    assertEquals(StatusCode.OK, workspace.nextSpilled(
        COLUMNS, new long[COLUMNS], values));
    assertTrue(workspace.nullAt(63));
    assertTrue(workspace.nullAt(64));
    assertTrue(workspace.nullAt(129));
    assertFalse(workspace.nullAt(62));
    assertEquals(StatusCode.OK, workspace.close());
    runtime.close();
  }

  @Test
  void spilledWideDecimalSortPreservesAndOrdersBothLanes(@TempDir Path root) {
    SqlMaterializedTestRuntime runtime = SqlMaterializedTestRuntime.open(root);
    SqlSortWorkspace workspace = runtime.workspace();
    SqlProjectedRow projected = new SqlProjectedRow();
    projected.reset(1);
    int descriptor = SqlTypeDescriptor.decimal(22, 18);
    assertEquals(StatusCode.OK, workspace.begin(
        new TableDefinition(), false, 1, false, false, false, descriptor));
    for (int row = 0; row < 1_024; row++) {
      projected.setDecimal128(0, 0, row);
      assertEquals(StatusCode.OK, workspace.append(
          0, row, false, row + 1L,
          projected.highs(), projected.values(), projected, null, projected));
    }
    projected.setDecimal128(0, -1, -1);
    assertEquals(StatusCode.OK, workspace.append(
        -1, -1, false, 2_000,
        projected.highs(), projected.values(), projected, null, projected));
    assertEquals(StatusCode.OK, workspace.finish());
    long[] highs = new long[1];
    long[] values = new long[1];
    assertEquals(StatusCode.OK, workspace.nextSpilled(1, highs, values));
    assertEquals(-1, highs[0]);
    assertEquals(-1, values[0]);
    assertEquals(2_000, workspace.outputPrimaryKey());
    assertEquals(StatusCode.OK, workspace.close());
    runtime.close();
  }

  @Test
  void groupedLookaheadCopiesEveryNullWordWithoutAllocationPerTake() {
    SqlActiveScanState scan = new SqlActiveScanState();
    assertEquals(StatusCode.OK, scan.groupLookahead().reserve(COLUMNS));
    SqlProjectedRow source = projected();
    source.setNull(65);
    source.setNull(128);
    long[] values = source.values();
    assertEquals(StatusCode.OK, scan.groupLookahead().set(
        source.highs(), values, COLUMNS, source));
    SqlProjectedRow restored = projected();
    assertEquals(StatusCode.OK, scan.groupLookahead().take(values, COLUMNS, restored));
    assertTrue(restored.isNull(65));
    assertTrue(restored.isNull(128));
    assertFalse(restored.isNull(64));
  }

  @Test
  void aggregateOperandLane129PreservesNullness() {
    SqlBoundAggregateSet aggregates = new SqlBoundAggregateSet();
    assertEquals(StatusCode.OK, aggregates.reserve(1));
    aggregates.append(
        SqlAggregateKind.COUNT_VALUE, 129,
        SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT);
    assertEquals(129, aggregates.operandLane(0));
    SqlAggregateAccumulatorSet accumulators = new SqlAggregateAccumulatorSet(
        new SqlSessionShapeBudget(null));
    assertEquals(StatusCode.OK,
        SqlAggregateAccumulatorCapacity.reserve(accumulators, aggregates));
    accumulators.reset(aggregates);
    SqlProjectedRow row = projected();
    row.setNull(129);
    assertEquals(StatusCode.OK, accumulators.accumulate(
        aggregates, new SqlBoundProjectionPrograms(), row, null, null));
    assertEquals(StatusCode.OK, accumulators.finish(aggregates));
    assertEquals(0, accumulators.value(0));
  }

  @Test
  void aggregateInvocationBoundariesReachSemanticMaximum() {
    int[] boundaries = {8, 9, 63, 64, 65, SqlShapeLimits.MAX_AGGREGATES};
    for (int count : boundaries) assertAggregateCount(count);
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        new SqlBoundAggregateSet().reserve(SqlShapeLimits.MAX_AGGREGATES + 1));
  }

  @Test
  void aggregateOperandAtMaximumResultOrdinalPreservesNullness() {
    SqlBoundAggregateSet aggregates = new SqlBoundAggregateSet();
    assertEquals(StatusCode.OK, aggregates.reserve(1));
    aggregates.append(
        SqlAggregateKind.COUNT_VALUE,
        SqlShapeLimits.MAX_RESULT_COLUMNS - 1,
        SqlTypeDescriptor.BIGINT,
        SqlTypeDescriptor.BIGINT);
    SqlAggregateAccumulatorSet accumulators = new SqlAggregateAccumulatorSet(
        new SqlSessionShapeBudget(null));
    assertEquals(StatusCode.OK,
        SqlAggregateAccumulatorCapacity.reserve(accumulators, aggregates));
    accumulators.reset(aggregates);
    SqlProjectedRow row = new SqlProjectedRow();
    row.reset(SqlShapeLimits.MAX_RESULT_COLUMNS);
    assertEquals(StatusCode.OK, row.status());
    row.setNull(SqlShapeLimits.MAX_RESULT_COLUMNS - 1);
    assertEquals(StatusCode.OK, accumulators.accumulate(
        aggregates, new SqlBoundProjectionPrograms(), row, null, null));
    assertEquals(0, accumulators.value(0));
  }

  private static void assertAggregateCount(int count) {
    SqlBoundAggregateSet aggregates = new SqlBoundAggregateSet();
    assertEquals(StatusCode.OK, aggregates.reserve(count));
    for (int invocation = 0; invocation < count; invocation++) {
      aggregates.append(
          SqlAggregateKind.COUNT, -1,
          SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT);
    }
    SqlAggregateAccumulatorSet accumulators = new SqlAggregateAccumulatorSet(
        new SqlSessionShapeBudget(null));
    assertEquals(StatusCode.OK,
        SqlAggregateAccumulatorCapacity.reserve(accumulators, aggregates));
    accumulators.reset(aggregates);
    assertEquals(StatusCode.OK, accumulators.accumulate(
        aggregates, new SqlBoundProjectionPrograms(),
        new SqlProjectedRow(), null, null));
    assertEquals(1, accumulators.value(count - 1));
  }

  private static SqlProjectedRow projected() {
    SqlProjectedRow projected = new SqlProjectedRow();
    projected.reset(COLUMNS);
    assertEquals(StatusCode.OK, projected.status());
    return projected;
  }

  private static int[] descriptors() {
    int[] descriptors = new int[COLUMNS];
    for (int column = 0; column < COLUMNS; column++) {
      descriptors[column] = SqlTypeDescriptor.BIGINT;
    }
    return descriptors;
  }
}
