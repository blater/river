package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.runtime.SqlRuntimeLease;
import io.riverdb.engine.runtime.SqlRuntimeLeaseResult;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagePin;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchFile;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchFileKind;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchOwner;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchStore;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlMaterializedStatementTest {
  @Test
  void lazilySharesOwnerAndRetainsItAcrossCleanupRetry(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        databaseRequest(4),
        root, DatabaseIncarnation.of(901, 902), WalGeneration.of(1), 4, opened));
    SqlRuntimeLeaseResult leaseResult = new SqlRuntimeLeaseResult();
    assertEquals(StatusCode.OK, opened.database().services().acquireRuntime(leaseResult));
    SqlRuntimeLease lease = leaseResult.lease();
    SqlMaterializedStatement statement = new SqlMaterializedStatement(lease);
    SqlMaterializedScratchOwner.Result ownerResult = new SqlMaterializedScratchOwner.Result();
    SqlMaterializedScratchStore.Result first = new SqlMaterializedScratchStore.Result();
    SqlMaterializedScratchStore.Result second = new SqlMaterializedScratchStore.Result();
    StatusDetail detail = new StatusDetail(256);

    assertFalse(statement.active());
    assertEquals(StatusCode.OK, statement.openStore(ownerResult, first, detail));
    assertTrue(statement.active());
    assertEquals(StatusCode.OK, statement.openStore(null, second, detail));
    assertNotSame(first.store(), second.store());

    SqlMaterializedScratchFile.Result file = new SqlMaterializedScratchFile.Result();
    assertEquals(StatusCode.OK, first.store().open(
        SqlMaterializedScratchFileKind.ROWS, file, detail));
    SqlMaterializedPagePin pin = new SqlMaterializedPagePin();
    assertEquals(StatusCode.OK, ownerResult.owner().pinNew(file.file(), 0, pin));
    assertEquals(StatusCode.INVARIANT_BROKEN, statement.close(detail));
    assertTrue(statement.active());
    assertEquals(StatusCode.OK, ownerResult.owner().unpin(pin));
    assertEquals(StatusCode.OK, statement.close(detail));
    assertFalse(statement.active());
    assertEquals(StatusCode.OK, lease.close());
    assertEquals(StatusCode.OK, opened.database().close());
  }

  @Test
  void nullLeaseCannotOpenMaterializedState() {
    SqlMaterializedStatement statement = new SqlSessionShapeBudget(null).materialized();
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, statement.openStore(
        new SqlMaterializedScratchOwner.Result(),
        new SqlMaterializedScratchStore.Result(), new StatusDetail(64)));
    assertFalse(statement.active());
  }

  @Test
  void reusesClosedStreamSlotAtItsHighWater(@TempDir Path root) {
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(root);
    SqlMaterializedStatement statement = fixture.budget().materialized();
    SqlMaterializedPagedByteStream.Result result =
        new SqlMaterializedPagedByteStream.Result();
    SqlMaterializedPagedByteStream.AppendResult append =
        new SqlMaterializedPagedByteStream.AppendResult();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK, statement.openStream(
        SqlMaterializedScratchFileKind.ROWS, 0, 0, result, detail));
    SqlMaterializedPagedByteStream first = result.stream();
    assertEquals(StatusCode.OK, first.append(ByteBuffer.wrap(new byte[] {1, 2}), append, detail));
    assertEquals(StatusCode.OK, first.close(detail));
    assertEquals(StatusCode.OK, statement.openStream(
        SqlMaterializedScratchFileKind.ROWS, 0, 0, result, detail));
    assertSame(first, result.stream());
    assertEquals(0, result.stream().logicalLength());
    assertEquals(StatusCode.OK, result.stream().close(detail));
    fixture.close();
  }

  @Test
  void nestedSortReservationsReleaseOnlyTheirOwnPages(@TempDir Path root) {
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(root);
    SqlMaterializedStatement statement = fixture.budget().materialized();
    SqlMaterializedPagedByteStream.Result result =
        new SqlMaterializedPagedByteStream.Result();
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.OK, statement.openStream(
        SqlMaterializedScratchFileKind.RUNS0, Long.BYTES, 0, result, detail));
    SqlMaterializedSortReservation outer = new SqlMaterializedSortReservation();
    SqlMaterializedSortReservation inner = new SqlMaterializedSortReservation();

    assertEquals(StatusCode.OK, statement.reserveSortPages(outer, 2));
    assertEquals(StatusCode.OK, statement.reserveSortPages(inner, 2));
    assertEquals(StatusCode.OK, statement.releaseSortPages(inner));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, statement.releaseSortPages(inner));
    assertEquals(StatusCode.OK, statement.releaseSortPages(outer));

    assertEquals(StatusCode.OK, result.stream().close(detail));
    fixture.close();
  }
}
