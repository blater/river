package io.riverdb.engine.table;

import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.tx.TransactionManager;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedSessionContextTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(9_211, 9_223);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void bindsOneDatabaseOwnershipSetAndOwnsItsSessionRegistry(@TempDir Path root) {
    Fixture fixture = fixture(root);
    TransactionManager manager = manager(fixture.table);
    IndexedVacuum vacuum = new IndexedVacuum(manager, fixture.table);
    IndexedSessionContext.Result bound = new IndexedSessionContext.Result();

    assertEquals(StatusCode.OK,
        IndexedSessionContext.bind(manager, fixture.table, null, vacuum, bound));
    IndexedTransactionSessionOpenResult opened = new IndexedTransactionSessionOpenResult();
    assertEquals(StatusCode.OK, bound.context().openSession(Long.BYTES, opened));
    assertEquals(1, bound.context().registry().count());
    assertEquals(StatusCode.OK, opened.session().close());
    assertEquals(0, bound.context().registry().count());

    close(fixture);
  }

  @Test
  void rejectsMixedDatabaseComponentsWithoutPublishingContext(@TempDir Path root) {
    Fixture first = fixture(root.resolve("first"));
    Fixture second = fixture(root.resolve("second"));
    TransactionManager firstManager = manager(first.table);
    TransactionManager secondManager = manager(second.table);
    IndexedVacuum firstVacuum = new IndexedVacuum(firstManager, first.table);
    IndexedSessionContext.Result result = new IndexedSessionContext.Result();

    assertEquals(StatusCode.NOT_OWNER,
        IndexedSessionContext.bind(
            secondManager, first.table, null, firstVacuum, result));
    assertNull(result.context());
    assertEquals(StatusCode.NOT_OWNER,
        IndexedSessionContext.bind(
            firstManager, second.table, null, firstVacuum, result));
    assertNull(result.context());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        IndexedSessionContext.bind(firstManager, first.table, null, null, result));
    assertNull(result.context());

    close(first);
    close(second);
  }

  private static Fixture fixture(Path root) {
    try {
      java.nio.file.Files.createDirectories(root);
    } catch (java.io.IOException failure) {
      throw new AssertionError(failure);
    }
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    assertEquals(StatusCode.OK, NioDurableDirectory.openExisting(
        root, new FatalStateFence(), new NioIoCounters(), 8, directoryResult));
    NioDurableDirectory directory = directoryResult.directory();
    LocalWalOpenResult walResult = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(directory, DATABASE, GENERATION, walResult));
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(StatusCode.OK, IndexedTableStore.create(
        directory, walResult.wal(), DATABASE, GENERATION,
        databaseProviderLease(4), storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(storeResult.store(), tableResult));
    return new Fixture(directory, walResult.wal(), tableResult.table());
  }

  private static TransactionManager manager(IndexedTable table) {
    return new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 2);
  }

  private static void close(Fixture fixture) {
    assertEquals(StatusCode.OK, fixture.table.flush());
    assertEquals(StatusCode.OK, fixture.table.close());
    assertEquals(StatusCode.OK, fixture.wal.close());
    assertEquals(StatusCode.OK, fixture.directory.close());
  }

  private record Fixture(
      NioDurableDirectory directory, LocalWal wal, IndexedTable table) {}
}
