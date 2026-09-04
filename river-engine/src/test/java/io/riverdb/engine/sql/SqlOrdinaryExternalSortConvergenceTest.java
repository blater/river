package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.runtime.RiverRuntimeConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlOrdinaryExternalSortConvergenceTest {
  @Test
  void mergesMoreThanSixtyFourRunsWithOddTailDescendingAndStableTies(
      @TempDir Path root) throws IOException {
    Files.writeString(
        root.resolve(RiverRuntimeConfig.FILE_NAME),
        "river.sql.materialized.page=8KB\n"
            + "river.sql.materialized.cache=64KB\n"
            + "river.sql.materialized.sort-run=16KB\n",
        StandardCharsets.UTF_8);
    SqlMaterializedTestRuntime runtime = SqlMaterializedTestRuntime.open(root);
    SqlSortWorkspace workspace = runtime.workspace();
    SqlProjectedRow projected = new SqlProjectedRow();
    projected.reset(1);
    assertEquals(StatusCode.OK, workspace.begin(
        new TableDefinition(), true, 1, false, false, false, SqlTypeDescriptor.BIGINT));
    int rowCount = workspace.configuredRunRows() * 65 + 1;
    for (int row = 0; row < rowCount; row++) {
      long key = row % 17;
      assertEquals(StatusCode.OK, workspace.append(
          0, key, false, row + 1L,
          projected.highs(), projected.values(), projected, null, projected));
    }
    assertEquals(StatusCode.OK, workspace.finish());
    assertTrue(workspace.isSpilled());
    long[] highs = new long[1];
    long[] values = new long[1];
    for (long key = 16; key >= 0; key--) {
      for (long row = key; row < rowCount; row += 17) {
        assertEquals(StatusCode.OK, workspace.nextSpilled(1, highs, values));
        assertEquals(row + 1, workspace.outputPrimaryKey());
      }
    }
    assertEquals(StatusCode.CONFLICT, workspace.nextSpilled(1, highs, values));
    assertEquals(StatusCode.OK, workspace.close());
    runtime.close();
  }

  @Test
  void fourWayMergePreservesGeneratedTextAndStableTiesAcrossMultipleGroups(
      @TempDir Path root) throws IOException {
    Files.writeString(
        root.resolve(RiverRuntimeConfig.FILE_NAME),
        "river.sql.materialized.page=8KB\n"
            + "river.sql.materialized.cache=64KB\n"
            + "river.sql.materialized.sort-run=32KB\n",
        StandardCharsets.UTF_8);
    SqlMaterializedTestRuntime runtime = SqlMaterializedTestRuntime.open(root);
    SqlSortWorkspace workspace = runtime.workspace();
    SqlProjectedRow projected = new SqlProjectedRow();
    assertEquals(StatusCode.OK, workspace.begin(
        new TableDefinition(), false, 1, false, true, false, SqlTypeDescriptor.BIGINT));
    int rowCount = workspace.configuredRunRows() * 5 + 1;
    for (int row = 0; row < rowCount; row++) {
      projected.reset(1);
      projected.setValue(0, row);
      projected.setText(0, new char[] {(char) ('A' + row % 26)}, 1);
      assertEquals(StatusCode.OK, projected.status());
      assertEquals(StatusCode.OK, workspace.append(
          0, row % 3, false, row + 1L,
          projected.highs(), projected.values(), projected, null, projected));
    }
    assertEquals(StatusCode.OK, workspace.finish());
    assertTrue(workspace.isSpilled());
    long[] highs = new long[1];
    long[] values = new long[1];
    SqlProjectedRow output = new SqlProjectedRow();
    output.reset(1);
    for (int key = 0; key < 3; key++) {
      for (int row = key; row < rowCount; row += 3) {
        assertEquals(StatusCode.OK, workspace.nextSpilled(1, highs, values));
        assertEquals(row + 1L, workspace.outputPrimaryKey());
        assertEquals(row, values[0]);
        workspace.copyGeneratedText(output, 0);
        assertEquals(1, output.textLength(0));
        assertEquals((char) ('A' + row % 26), output.textCharacter(0, 0));
      }
    }
    assertEquals(StatusCode.CONFLICT, workspace.nextSpilled(1, highs, values));
    assertEquals(StatusCode.OK, workspace.close());
    runtime.close();
  }
}
