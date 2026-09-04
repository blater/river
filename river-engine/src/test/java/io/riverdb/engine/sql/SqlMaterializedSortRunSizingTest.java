package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class SqlMaterializedSortRunSizingTest {
  @Test
  void reservesOnlyTheConfiguredRunPagesRequiredByCurrentCardinality() {
    assertEquals(2, SqlMaterializedSortRunSizing.pages(1_000, 64_000, 300));
    assertEquals(3, SqlMaterializedSortRunSizing.pages(1_000, 8_000, 2_000));
    assertEquals(1_000, SqlMaterializedSortRunSizing.pages(
        1_000, 8_000, Long.MAX_VALUE));
  }

  @Test
  void rejectsInvalidRuntimeInputs() {
    assertEquals(-1, SqlMaterializedSortRunSizing.pages(1, 8_000, 1));
    assertEquals(-1, SqlMaterializedSortRunSizing.pages(2, 32, 1));
    assertEquals(-1, SqlMaterializedSortRunSizing.pages(2, 8_000, -1));
  }
}
