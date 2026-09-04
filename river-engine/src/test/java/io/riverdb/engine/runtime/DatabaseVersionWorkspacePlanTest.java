package io.riverdb.engine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class DatabaseVersionWorkspacePlanTest {
  @Test
  void derivesCapacityAtPrimitivePageBoundaries() {
    long onePage = DatabaseVersionWorkspacePlan.retainedBytes(
        DatabasePrimitiveChunkLayout.PAGE_SIZE);
    long twoPages = DatabaseVersionWorkspacePlan.retainedBytes(
        DatabasePrimitiveChunkLayout.PAGE_SIZE + 1);
    DatabaseVersionWorkspacePlan.Result result =
        new DatabaseVersionWorkspacePlan.Result();

    assertEquals(StatusCode.OK, DatabaseVersionWorkspacePlan.compile(onePage, result));
    assertEquals(DatabasePrimitiveChunkLayout.PAGE_SIZE,
        result.plan().maximumOperations());
    assertEquals(onePage, result.plan().maximumRetainedBytes());

    assertEquals(StatusCode.OK,
        DatabaseVersionWorkspacePlan.compile(twoPages - 1, result));
    assertEquals(DatabasePrimitiveChunkLayout.PAGE_SIZE,
        result.plan().maximumOperations());
    assertEquals(StatusCode.OK, DatabaseVersionWorkspacePlan.compile(twoPages, result));
    assertTrue(result.plan().maximumOperations()
        > DatabasePrimitiveChunkLayout.PAGE_SIZE);
  }

  @Test
  void rejectsBudgetThatCannotRepresentOneStructuralPage() {
    long firstPage = DatabaseVersionWorkspacePlan.retainedBytes(1);
    DatabaseVersionWorkspacePlan.Result result =
        new DatabaseVersionWorkspacePlan.Result();

    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        DatabaseVersionWorkspacePlan.compile(firstPage - 1, result));
    assertNull(result.plan());
  }
}
