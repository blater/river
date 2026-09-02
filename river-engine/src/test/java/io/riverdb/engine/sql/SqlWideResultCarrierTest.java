package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import org.junit.jupiter.api.Test;

final class SqlWideResultCarrierTest {
  @Test
  void executionResultCarriesNullsAcrossEveryWordBoundary() {
    int[] descriptors = descriptors(SqlShapeLimits.MAX_RESULT_COLUMNS);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK,
        result.beginProjection(7, descriptors, descriptors.length, 11));

    int[] nulls = {0, 7, 8, 63, 64, 65, 255, 1_023, 1_663};
    for (int index = 0; index < descriptors.length; index++) {
      result.setProjectedValue(index, index);
    }
    for (int index : nulls) result.setProjectedNull(index);

    assertEquals(SqlShapeLimits.MAX_RESULT_COLUMNS, result.columnCount());
    assertEquals(26, result.nullWordCount());
    for (int index : nulls) assertTrue(result.isNull(index), "null lane " + index);
    assertFalse(result.isNull(9));
    assertEquals(1L << 1, result.nullWord(1) & 1L << 1);
  }

  @Test
  void scanResultAdmitsDiscontinuitiesAndRejectsLane1665() {
    int[] boundaries = {8, 9, 63, 64, 65, 1_024, 1_664};
    SqlScanRowResult result = new SqlScanRowResult();
    for (int columns : boundaries) {
      int[] descriptors = descriptors(columns);
      assertEquals(StatusCode.OK, result.beginProjected(1, descriptors, columns));
      result.setProjectedNull(columns - 1);
      assertTrue(result.isNull(columns - 1));
      assertEquals(columns, result.columnCount());
    }
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        result.beginProjected(1, descriptors(1_665), 1_665));
  }

  @Test
  void failedPublisherGrowthPreservesPreviouslyAdmittedCapacity() {
    SqlPublicResultPublisher publisher = new SqlPublicResultPublisher();

    assertEquals(StatusCode.OK, publisher.reserve(8));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, publisher.reserve(1_665));
    assertEquals(StatusCode.OK, publisher.reserve(8));
  }

  @Test
  void failedMutationStateGrowthPreservesPriorLogicalState() {
    SqlMutationEvaluationState state = new SqlMutationEvaluationState();
    assertEquals(StatusCode.OK, state.reserve(2));
    state.set(1, 41, SqlTypeDescriptor.BIGINT, true);

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, state.reserve(1_025));
    assertEquals(41, state.value(1));
    assertEquals(SqlTypeDescriptor.BIGINT, state.descriptor(1));
    assertTrue(state.isNull(1));
    state.reset();
  }

  private static int[] descriptors(int columns) {
    int[] descriptors = new int[columns];
    for (int index = 0; index < columns; index++) {
      descriptors[index] = SqlTypeDescriptor.BIGINT;
    }
    return descriptors;
  }
}
