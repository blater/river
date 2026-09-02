package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

final class SqlSortConfiguredRunTest {
  @Test
  void admittedSortPagesScaleResidentRunAndMergeFanIn(@TempDir Path root)
      throws IOException {
    int twoPages = configuredRows(root.resolve("two"), "16KB");
    int fourPages = configuredRows(root.resolve("four"), "32KB");
    assertEquals(twoPages * 2, fourPages);
  }

  @Test
  void failedRunAdmissionPublishesNoResourcesAndRetries(@TempDir Path root) {
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(root);
    SqlSessionShapeBudget budget = fixture.budget();
    SqlSortWorkspace workspace = new SqlSortWorkspace(
        SqlRetainedArrayAllocator.STANDARD, budget);
    long occupied = budget.maximumBytes();
    assertEquals(StatusCode.OK, budget.reserve(occupied));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, workspace.begin(
        new TableDefinition(), false, 1, false, false, false, SqlTypeDescriptor.BIGINT));
    assertFalse(workspace.hasResources());
    budget.rollback(occupied);
    assertEquals(StatusCode.OK, workspace.begin(
        new TableDefinition(), false, 1, false, false, false, SqlTypeDescriptor.BIGINT));
    assertEquals(StatusCode.OK, workspace.close());
    fixture.close();
  }

  private static int configuredRows(Path root, String sortRun) throws IOException {
    Files.createDirectories(root);
    Files.writeString(
        root.resolve(RiverRuntimeConfig.FILE_NAME),
        "river.sql.materialized.page=8KB\n"
            + "river.sql.materialized.cache=64KB\n"
            + "river.sql.materialized.sort-run=" + sortRun + "\n",
        StandardCharsets.UTF_8);
    SqlMaterializedTestRuntime runtime = SqlMaterializedTestRuntime.open(root);
    SqlSortWorkspace workspace = runtime.workspace();
    assertEquals(StatusCode.OK, workspace.begin(
        new TableDefinition(), false, 1, false, false, false, SqlTypeDescriptor.BIGINT));
    int rows = workspace.configuredRunRows();
    assertEquals(StatusCode.OK, workspace.close());
    runtime.close();
    return rows;
  }
}
