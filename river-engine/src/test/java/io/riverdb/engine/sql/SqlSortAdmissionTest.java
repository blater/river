package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.runtime.RiverRuntimeConfig;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchFileKind;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlSortAdmissionTest {
  @Test
  void defaultRuntimeJointlyAdmitsTheGreatestResidentAndMergeShape(@TempDir Path root) {
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(
        root, 2_048_000_000L);
    SqlSessionShapeBudget budget = fixture.budget();
    SqlSortWorkspace workspace = new SqlSortWorkspace(
        SqlRetainedArrayAllocator.STANDARD, budget);

    assertEquals(StatusCode.OK, workspace.begin(
        new TableDefinition(), false, 1, false, false, false, 0));
    int admittedPages = workspace.admittedRunPages();
    assertTrue(admittedPages >= RiverRuntimeConfig.MINIMUM_SORT_RUN_PAGES);
    assertTrue(admittedPages <= fixture.configuredSortRunPages());
    long exactBytes = SqlSortAdmission.cleanRequiredBytes(
        admittedPages, fixture.pageBytes(), 1, false, false);
    assertEquals(exactBytes, workspace.retainedBytes());
    assertEquals(exactBytes, budget.retainedBytes());
    if (admittedPages < fixture.configuredSortRunPages()) {
      assertTrue(SqlSortAdmission.cleanRequiredBytes(
          admittedPages + 1, fixture.pageBytes(), 1, false, false)
          > budget.maximumBytes());
    }

    assertEquals(StatusCode.OK, workspace.close());
    fixture.close();
  }

  @Test
  void defaultRuntimeReplacesIncompatibleRetainedShapesBeforeActivation(
      @TempDir Path root) {
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(
        root, 2_048_000_000L);
    SqlSessionShapeBudget budget = fixture.budget();
    SqlSortWorkspace workspace = new SqlSortWorkspace(
        SqlRetainedArrayAllocator.STANDARD, budget);

    assertEquals(StatusCode.OK, workspace.begin(
        new TableDefinition(), false, 1, false, true, false, 0));
    assertEquals(StatusCode.OK, workspace.close());

    assertEquals(StatusCode.OK, workspace.begin(
        new TableDefinition(), false, 5, false, true, false, 0));
    long cleanBytes = SqlSortAdmission.cleanRequiredBytes(
        workspace.admittedRunPages(), fixture.pageBytes(), 5, false, true);
    assertEquals(cleanBytes, workspace.retainedBytes());
    assertEquals(cleanBytes, budget.retainedBytes());
    assertEquals(StatusCode.OK, workspace.close());

    for (int repetition = 0; repetition < 2; repetition++) {
      assertEquals(StatusCode.OK, workspace.begin(
          new TableDefinition(), false, 1, false, true, false, 0));
      assertEquals(workspace.retainedBytes(), budget.retainedBytes());
      assertEquals(StatusCode.OK, workspace.close());
      assertEquals(StatusCode.OK, workspace.begin(
          new TableDefinition(), false, 5, false, true, false, 0));
      assertEquals(workspace.retainedBytes(), budget.retainedBytes());
      assertEquals(StatusCode.OK, workspace.close());
    }
    fixture.close();
  }

  @Test
  void everyWideTextAllocationFailsBeforeActivationAndRetries() {
    CountingAllocator counter = new CountingAllocator();
    SqlSortWorkspace counted = new SqlSortWorkspace(counter);
    assertEquals(StatusCode.OK,
        counted.begin(new TableDefinition(), false, 9, true, true, false, 0));
    assertEquals(StatusCode.OK, counted.close());
    for (int failure = 1; failure <= counter.calls; failure++) {
      CountingAllocator allocator = new CountingAllocator();
      allocator.failure = failure;
      allocator.failPersistently = true;
      SqlSessionShapeBudget budget = new SqlSessionShapeBudget(null);
      SqlSortWorkspace workspace = new SqlSortWorkspace(allocator, budget);
      assertEquals(StatusCode.RESOURCE_EXHAUSTED,
          workspace.begin(new TableDefinition(), false, 9, true, true, false, 0));
      assertFalse(workspace.hasResources());
      assertEquals(workspace.retainedBytes(), budget.retainedBytes());
      allocator.failure = 0;
      assertEquals(StatusCode.OK,
          workspace.begin(new TableDefinition(), false, 9, true, true, false, 0));
      assertEquals(workspace.retainedBytes(), budget.retainedBytes());
      assertEquals(StatusCode.OK, workspace.close());
      assertEquals(workspace.retainedBytes(), budget.retainedBytes());
    }
  }

  @Test
  void spillPreparationReplacesItsOwnStaleShapeUnderBudgetPressure() {
    SqlSessionShapeBudget targetBudget = new SqlSessionShapeBudget(null);
    SqlSortSpillStorage target = new SqlSortSpillStorage(
        SqlRetainedArrayAllocator.STANDARD, targetBudget);
    assertEquals(StatusCode.OK, target.prepare(1, true, false, 2));
    long targetBytes = target.retainedBytes();
    target.deactivate();
    assertEquals(StatusCode.OK, targetBudget.release(targetBytes));
    target.releaseRetainedStorage();

    SqlSessionShapeBudget budget = new SqlSessionShapeBudget(null);
    SqlSortSpillStorage storage = new SqlSortSpillStorage(
        SqlRetainedArrayAllocator.STANDARD, budget);
    assertEquals(StatusCode.OK, storage.prepare(
        SqlShapeLimits.MAX_RESULT_COLUMNS, false, true, 2));
    storage.deactivate();
    long staleBytes = storage.retainedBytes();
    assertTrue(staleBytes >= targetBytes);

    long occupied = budget.maximumBytes() - staleBytes;
    assertEquals(StatusCode.OK, budget.reserve(occupied));
    assertEquals(budget.maximumBytes(), budget.retainedBytes());

    assertEquals(StatusCode.OK, storage.prepare(1, true, false, 2));
    assertEquals(targetBytes, storage.retainedBytes());
    assertEquals(occupied + targetBytes, budget.retainedBytes());

    storage.deactivate();
    assertEquals(StatusCode.OK, budget.release(occupied + targetBytes));
    storage.releaseRetainedStorage();
    assertEquals(0, storage.retainedBytes());
    assertEquals(0, budget.retainedBytes());
  }

  @Test
  void failedPageReservationClosesSourceAndRetryMustAcquire(@TempDir Path root) {
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(root);
    SqlMaterializedStatement statement = fixture.budget().materialized();
    SqlMaterializedPagedByteStream.Result opened =
        new SqlMaterializedPagedByteStream.Result();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK, statement.openStream(
        SqlMaterializedScratchFileKind.ROWS, 0, 0, opened, detail));
    assertEquals(StatusCode.OK, opened.stream().close(detail));
    ArrayList<SqlMaterializedSortReservation> blockers = new ArrayList<>();
    int remaining = fixture.cachePages();
    int maximumTotal = fixture.configuredSortRunPages() + 2;
    while (remaining > 0) {
      int total = Math.min(remaining, maximumTotal);
      assertTrue(total >= RiverRuntimeConfig.MINIMUM_SORT_RUN_PAGES + 2);
      SqlMaterializedSortReservation blocker = new SqlMaterializedSortReservation();
      assertEquals(StatusCode.OK, statement.reserveSortPages(blocker, total - 2));
      blockers.add(blocker);
      remaining -= total;
    }

    SqlSortSpillStreams streams = new SqlSortSpillStreams(statement);
    assertEquals(StatusCode.OK,
        streams.configure(RiverRuntimeConfig.MINIMUM_SORT_RUN_PAGES));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, streams.ensureSource());
    assertEquals(null, streams.source());

    SqlMaterializedSortReservation released = blockers.remove(blockers.size() - 1);
    assertEquals(StatusCode.OK, statement.releaseSortPages(released));
    assertEquals(StatusCode.OK, streams.ensureSource());
    assertTrue(streams.source() != null);
    assertEquals(StatusCode.OK, streams.close());

    for (SqlMaterializedSortReservation blocker : blockers) {
      assertEquals(StatusCode.OK, statement.releaseSortPages(blocker));
    }
    fixture.close();
  }

  @Test
  void closedWideArraysRemainWarmUntilConfiguredBudgetPressure(@TempDir Path root) {
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(root);
    SqlSessionShapeBudget budget = fixture.budget();
    SqlSortWorkspace workspace = new SqlSortWorkspace(
        SqlRetainedArrayAllocator.STANDARD, budget);
    assertEquals(
        StatusCode.OK,
        workspace.begin(new TableDefinition(), false, 65, false, true, false, 0));
    long cached = workspace.retainedProjectionBytes();
    assertTrue(cached > 0);
    assertEquals(workspace.retainedBytes(), budget.retainedBytes());
    assertEquals(budget.retainedBytes(), fixture.leaseReservedBytes());
    assertEquals(StatusCode.OK, workspace.close());
    assertEquals(cached, workspace.retainedProjectionBytes());
    assertEquals(workspace.retainedBytes(), budget.retainedBytes());
    assertEquals(budget.retainedBytes(), fixture.leaseReservedBytes());

    long occupied = budget.maximumBytes() - budget.retainedBytes();
    assertEquals(StatusCode.OK, budget.reserve(occupied));
    assertEquals(StatusCode.OK, budget.reserve(1));
    assertEquals(0, workspace.retainedProjectionBytes());
    assertEquals(0, workspace.retainedBytes());
    assertEquals(budget.retainedBytes(), fixture.leaseReservedBytes());

    budget.rollback(occupied + 1);
    assertEquals(0, budget.retainedBytes());
    assertEquals(0, fixture.leaseReservedBytes());
    assertEquals(
        StatusCode.OK,
        workspace.begin(new TableDefinition(), false, 65, false, true, false, 0));
    assertEquals(cached, workspace.retainedProjectionBytes());
    assertEquals(workspace.retainedBytes(), budget.retainedBytes());
    assertEquals(budget.retainedBytes(), fixture.leaseReservedBytes());
    assertEquals(StatusCode.OK, workspace.close());
    fixture.close();
  }

  private static final class CountingAllocator extends SqlRetainedArrayAllocator {
    private int calls;
    private int failure;
    private boolean failPersistently;

    @Override byte[] bytes(int capacity) { hit(); return super.bytes(capacity); }
    @Override char[] characters(int capacity) {
      hit(); return super.characters(capacity);
    }
    @Override int[] integers(int capacity) { hit(); return super.integers(capacity); }
    @Override long[] longs(int capacity) { hit(); return super.longs(capacity); }
    @Override boolean[] booleans(int capacity) { hit(); return super.booleans(capacity); }
    @Override ByteBuffer direct(int capacity) { hit(); return super.direct(capacity); }

    private void hit() {
      calls++;
      if (failure != 0 && (calls == failure || failPersistently && calls > failure)) {
        throw new OutOfMemoryError("injected");
      }
    }
  }
}
