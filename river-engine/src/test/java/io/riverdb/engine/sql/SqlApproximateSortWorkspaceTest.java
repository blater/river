package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlApproximateSortWorkspaceTest {
  @Test
  void realAndDoubleKeysUseNumericOrdering() {
    assertOrder(SqlTypeDescriptor.REAL,
        SqlApproximateNumeric.realBits(-2.0f),
        SqlApproximateNumeric.realBits(1.0f),
        SqlApproximateNumeric.realBits(-0.5f));
    assertOrder(SqlTypeDescriptor.DOUBLE,
        SqlApproximateNumeric.doubleBits(-2.0d),
        SqlApproximateNumeric.doubleBits(1.0d),
        SqlApproximateNumeric.doubleBits(-0.5d));
  }

  @Test
  void spilledDoubleRunsUseNumericMergeOrdering(@TempDir Path root) {
    SqlMaterializedTestRuntime runtime = SqlMaterializedTestRuntime.open(root);
    SqlSortWorkspace workspace = runtime.workspace();
    SqlProjectedRow projected = new SqlProjectedRow();
    projected.reset(1);
    assertEquals(StatusCode.OK, workspace.begin(
        new TableDefinition(), false, 1, false, false, false, SqlTypeDescriptor.DOUBLE));
    int residentRows = workspace.configuredRunRows();
    for (int row = 0; row < residentRows; row++) {
      assertEquals(StatusCode.OK, workspace.append(
          0, SqlApproximateNumeric.doubleBits(row), false, row + 1L,
          projected.highs(), projected.values(), projected, null, projected));
    }
    assertEquals(StatusCode.OK, workspace.append(
        0, SqlApproximateNumeric.doubleBits(-2.0d), false, residentRows + 1L,
        projected.highs(), projected.values(), projected, null, projected));
    assertEquals(StatusCode.OK, workspace.finish());
    assertEquals(StatusCode.OK, workspace.nextSpilled(
        1, new long[1], new long[1]));
    assertEquals(residentRows + 1L, workspace.outputPrimaryKey());
    assertEquals(StatusCode.OK, workspace.close());
    runtime.close();
  }

  private static void assertOrder(int descriptor, long first, long second, long third) {
    SqlSortWorkspace workspace = new SqlSortWorkspace();
    SqlProjectedRow projected = new SqlProjectedRow();
    projected.reset(1);
    assertEquals(StatusCode.OK, workspace.begin(
        new TableDefinition(), false, 1, false, false, false, descriptor));
    assertEquals(StatusCode.OK, workspace.append(
        0, first, false, 1,
        projected.highs(), projected.values(), projected, null, projected));
    assertEquals(StatusCode.OK, workspace.append(
        0, second, false, 2,
        projected.highs(), projected.values(), projected, null, projected));
    assertEquals(StatusCode.OK, workspace.append(
        0, third, false, 3,
        projected.highs(), projected.values(), projected, null, projected));
    assertEquals(StatusCode.OK, workspace.finish());
    assertEquals(1, workspace.primaryKeyAt(0));
    assertEquals(3, workspace.primaryKeyAt(1));
    assertEquals(2, workspace.primaryKeyAt(2));
    assertEquals(StatusCode.OK, workspace.close());
  }
}
