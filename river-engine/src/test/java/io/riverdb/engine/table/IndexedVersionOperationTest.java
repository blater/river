package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.TestDatabaseResources;
import io.riverdb.engine.runtime.DatabaseVersionWorkspacePlan;
import org.junit.jupiter.api.Test;

final class IndexedVersionOperationTest {
  @Test
  void reservesStructuralVersionsBeyondRowCountAndReusesCapacity() {
    IndexedVersionOperation operation = new IndexedVersionOperation(
        TestDatabaseResources.databasePlan(1).versionWorkspace());
    assertEquals(0, operation.accountedBytes());
    assertEquals(0, operation.retainedBytes());
    assertEquals(1_025, IndexedVersionOperation.required(1_024, 1));
    assertEquals(StatusCode.OK, operation.reserve(1_025));
    stage(operation, 1_025);
    long retainedBytes = operation.accountedBytes();
    assertEquals(retainedBytes, operation.retainedBytes());

    operation.begin();
    assertEquals(StatusCode.OK, operation.reserve(1_025));
    stage(operation, 1_025);
    assertEquals(retainedBytes, operation.accountedBytes());
    assertEquals(StatusCode.OK, operation.release());
    assertEquals(0, operation.retainedBytes());
  }

  @Test
  void rejectsOnlyOrdinalOverflowRatherThanAnImplementationSizedCeiling() {
    assertEquals(
        Integer.MAX_VALUE,
        IndexedVersionOperation.required(Integer.MAX_VALUE - 1, 1));
    assertEquals(-1, IndexedVersionOperation.required(Integer.MAX_VALUE, 1));
  }

  @Test
  void rejectsWorkspaceBeyondItsByteBudgetBeforeAllocatingAnyChunk() {
    DatabaseVersionWorkspacePlan.Result result = new DatabaseVersionWorkspacePlan.Result();
    assertEquals(StatusCode.OK, DatabaseVersionWorkspacePlan.compile(1_000_000, result));
    IndexedVersionOperation operation = new IndexedVersionOperation(result.plan());

    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        operation.reserve(result.plan().maximumOperations() + 1));
    assertEquals(0, operation.accountedBytes());
    assertEquals(0, operation.retainedBytes());
  }

  private static void stage(IndexedVersionOperation operation, int count) {
    for (int index = 0; index < count; index++) {
      assertTrue(operation.canStage(0, false, 0));
      operation.stage(0, false, index, index);
    }
    assertEquals(count, operation.count());
  }
}
