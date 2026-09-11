package io.riverdb.engine.table;

import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedTableStoreInterruptedVacuumTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(877, 881);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final int BATCHES = 6;
  private static final int ROWS_PER_BATCH = 50;
  private static final int ROW_BYTES = 4096;

  @Test
  void vacuumTraversesSiblingOrderAfterNonRightmostSplits(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    commitDescendingVersions(table, 400, 50);
    assertEquals(800, table.rowCount());
    assertEquals(400, table.obsoleteVersionCount());
    assertTrue(table.treeHeight() >= 2);
    HeapRowResult retained = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(0, 200, retained));
    assertEquals(10_200, retained.getLong(0));

    IndexedVacuumResult vacuum = new IndexedVacuumResult();
    assertEquals(StatusCode.OK, table.vacuum(table.nextTransactionId(), vacuum));
    assertEquals(800, vacuum.rowsBefore());
    assertEquals(400, vacuum.rowsAfter());
    assertEquals(400, table.rowCount());
    assertEquals(0, table.obsoleteVersionCount());
    assertEquals(10_200, retained.getLong(0));

    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(0, 0, fetched));
    assertEquals(10_000, fetched.getLong(0));
    assertEquals(StatusCode.OK, table.fetchByKey(0, 200, fetched));
    assertEquals(10_200, fetched.getLong(0));
    assertEquals(StatusCode.OK, table.fetchByKey(0, 399, fetched));
    assertEquals(10_399, fetched.getLong(0));
    close(table, wal, directory);
  }

  @Test
  void admitsVacuumAboveLegacyAtomicPageBound(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTable table = createTable(createStore(directory, wal));
    commitWideVersions(table, 10);
    assertEquals(StatusCode.OK, table.vacuumPreflight());
    IndexedVacuumResult vacuum = new IndexedVacuumResult();
    assertEquals(StatusCode.OK, table.vacuum(table.nextTransactionId(), vacuum));
    assertEquals(1_000, vacuum.rowsBefore());
    assertEquals(500, vacuum.rowsAfter());
    assertEquals(500, table.rowCount());
    assertEquals(0, table.obsoleteVersionCount());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(0, 1000, fetched));
    assertEquals(11_000, fetched.getLong(0));
    close(table, wal, directory);
  }

  @Test
  void streamsMultiChunkVacuumThroughOneDurableDecision(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTableStore store = createStore(directory, wal);
    IndexedTable table = createTable(store);
    commitWideVersions(table);
    assertEquals(600, table.rowCount());
    assertEquals(300, table.obsoleteVersionCount());
    assertTrue(
        IndexedTableStore.VACUUM_COMMIT_PAYLOAD_BYTES
            + 300L * (ROW_BYTES + IndexedWalCodec.VACUUM_ENTRY_BYTES)
            > WalRecordCodec.MAX_PAYLOAD_BYTES);

    long walEnd = wal.tailEnd();
    assertEquals(StatusCode.OK, table.vacuumPreflight());
    assertEquals(walEnd, wal.tailEnd());
    IndexedVacuumResult vacuum = new IndexedVacuumResult();
    assertEquals(StatusCode.OK, table.vacuum(14, vacuum));
    assertTrue(wal.tailEnd() > walEnd);
    assertEquals(14, table.currentCommitSequence());
    assertEquals(300, table.rowCount());
    assertEquals(0, table.obsoleteVersionCount());
    IndexedVersionRecord version = new IndexedVersionRecord();
    assertEquals(StatusCode.OK, store.readVersion(1, version));
    assertEquals(14, version.commitSequence());
    assertEquals(0, version.previousRowId());
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(0, 1000, fetched));
    assertEquals(11_000, fetched.getLong(0));
    assertEquals(StatusCode.OK, table.fetchByKey(0, 1299, fetched));
    assertEquals(11_299, fetched.getLong(0));
    close(table, wal, directory);
  }

  private static void commitWideVersions(IndexedTable table) {
    commitWideVersions(table, BATCHES);
  }

  private static void commitWideVersions(IndexedTable table, int batches) {
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 5);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedSessionContext.Result contextResult = new IndexedSessionContext.Result();
    assertEquals(
        StatusCode.OK,
        IndexedSessionContext.bind(manager, table, null, vacuum, contextResult));
    IndexedTransactionSession writer = openSession(contextResult.context(), ROW_BYTES);
    TransactionOutcome outcome = new TransactionOutcome();
    ByteBuffer row = ByteBuffer.allocateDirect(ROW_BYTES);
    for (int batch = 0; batch < batches; batch++) {
      assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
      for (int index = 0; index < ROWS_PER_BATCH; index++) {
        long key = 1000L + batch * ROWS_PER_BATCH + index;
        row.putLong(0, key);
        row.position(0);
        row.limit(row.capacity());
        assertEquals(StatusCode.OK, writer.insert(0, key, row));
      }
      assertEquals(StatusCode.OK, writer.commit(outcome));
    }
    for (int batch = 0; batch < batches; batch++) {
      assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
      for (int index = 0; index < ROWS_PER_BATCH; index++) {
        long key = 1000L + batch * ROWS_PER_BATCH + index;
        row.putLong(0, key + 10_000);
        row.position(0);
        row.limit(row.capacity());
        assertEquals(StatusCode.OK, writer.update(0, key, row));
      }
      assertEquals(StatusCode.OK, writer.commit(outcome));
    }
  }

  private static void commitDescendingVersions(
      IndexedTable table, int rows, int batchSize) {
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 5);
    IndexedVacuum vacuum = new IndexedVacuum(manager, table);
    IndexedSessionContext.Result contextResult = new IndexedSessionContext.Result();
    assertEquals(
        StatusCode.OK,
        IndexedSessionContext.bind(manager, table, null, vacuum, contextResult));
    IndexedTransactionSession writer = openSession(contextResult.context(), Long.BYTES);
    TransactionOutcome outcome = new TransactionOutcome();
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    for (int pass = 0; pass < 2; pass++) {
      for (int first = 0; first < rows; first += batchSize) {
        assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
        int count = Math.min(batchSize, rows - first);
        for (int index = 0; index < count; index++) {
          long key = rows - 1L - first - index;
          row.putLong(0, key + pass * 10_000L);
          row.position(0);
          row.limit(row.capacity());
          StatusCode status = pass == 0
              ? writer.insert(0, key, row) : writer.update(0, key, row);
          assertEquals(StatusCode.OK, status);
        }
        assertEquals(StatusCode.OK, writer.commit(outcome));
      }
    }
  }

  private static NioDurableDirectory openDirectory(Path root) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root,
            new FatalStateFence(),
            new NioIoCounters(),
            8,
            result));
    return result.directory();
  }

  private static IndexedTransactionSession openSession(
      IndexedSessionContext context, int maximumRowBytes) {
    IndexedTransactionSessionOpenResult result = new IndexedTransactionSessionOpenResult();
    assertEquals(StatusCode.OK, context.openSession(maximumRowBytes, result));
    return result.session();
  }

  private static LocalWal openWal(NioDurableDirectory directory) {
    LocalWalOpenResult result = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.open(directory, DATABASE, GENERATION, result));
    return result.wal();
  }

  private static IndexedTableStore createStore(
      NioDurableDirectory directory,
      LocalWal wal) {
    IndexedTableStoreOpenResult result = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.create(
            directory, wal, DATABASE, GENERATION, databaseProviderLease(5), result));
    return result.store();
  }

  private static IndexedTable createTable(IndexedTableStore store) {
    IndexedTableOpenResult result = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(store, result));
    return result.table();
  }

  private static void close(
      IndexedTable table,
      LocalWal wal,
      NioDurableDirectory directory) {
    assertEquals(StatusCode.OK, table.flush());
    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }
}
