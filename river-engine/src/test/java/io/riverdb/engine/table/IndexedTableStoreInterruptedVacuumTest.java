package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.wal.WalFileHeaderCodec;
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
import io.riverdb.wal.local.LocalWalReadResult;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedTableStoreInterruptedVacuumTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(877, 881);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final int BATCHES = 6;
  private static final int ROWS_PER_BATCH = 50;
  private static final int ROW_BYTES = 4096;

  @Test
  void recoveryDiscardsEveryChunkOfInterruptedMultiChunkVacuum(@TempDir Path root)
      throws Exception {
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

    IndexedVacuumResult vacuum = new IndexedVacuumResult();
    assertEquals(StatusCode.OK, table.vacuum(14, vacuum));
    assertEquals(600, vacuum.rowsBefore());
    assertEquals(300, vacuum.rowsAfter());
    assertEquals(14, vacuum.commitSequence());
    assertEquals(300, table.rowCount());
    assertEquals(0, table.obsoleteVersionCount());

    int chunkCount = 0;
    int lastOperation = 0;
    long offset = WalFileHeaderCodec.HEADER_BYTES;
    LocalWalReadResult record = new LocalWalReadResult();
    while (offset < wal.tailEnd()) {
      assertEquals(StatusCode.OK, wal.read(offset, record));
      lastOperation = IndexedWalCodec.operationType(record.payload());
      if (lastOperation == IndexedWalCodec.OPERATION_TYPE_VACUUM_CHUNK) {
        chunkCount++;
      }
      offset = record.nextOffset();
    }
    assertTrue(chunkCount > 1);
    assertEquals(IndexedWalCodec.OPERATION_TYPE_VACUUM_COMMIT, lastOperation);

    long incompleteEnd = wal.durableEnd()
        - WalRecordCodec.encodedBytes(IndexedTableStore.VACUUM_COMMIT_PAYLOAD_BYTES);
    try (FileChannel channel = FileChannel.open(
        root.resolve(LocalWal.FILE_NAME), StandardOpenOption.WRITE)) {
      channel.truncate(incompleteEnd);
      channel.force(true);
    }

    assertEquals(StatusCode.OK, directory.advanceGeneration());
    assertEquals(StatusCode.OK, directory.close());
    directory = openDirectory(root);
    wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    store = storeResult.store();
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(store, tableResult));
    table = tableResult.table();

    assertEquals(13, table.currentCommitSequence());
    assertEquals(600, table.rowCount());
    assertEquals(300, table.obsoleteVersionCount());
    assertEquals(8, store.rowCommitSequence(301));
    assertEquals(1, store.previousRowId(301));
    HeapRowResult fetched = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey( 0,1000, fetched));
    assertEquals(11_000, fetched.getLong(0));
    assertEquals(StatusCode.OK, table.fetchByKey( 0,1299, fetched));
    assertEquals(11_299, fetched.getLong(0));
    assertEquals(StatusCode.OK, table.fetchByKeyAt(7, 0, 1000, fetched));
    assertEquals(1000, fetched.getLong(0));
    close(table, wal, directory);
  }

  private static void commitWideVersions(IndexedTable table) {
    TransactionManager manager = new TransactionManager(
        DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 5);
    IndexedTransactionSession writer = new IndexedTransactionSession(manager, table, ROW_BYTES);
    TransactionOutcome outcome = new TransactionOutcome();
    ByteBuffer row = ByteBuffer.allocateDirect(ROW_BYTES);
    for (int batch = 0; batch < BATCHES; batch++) {
      assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
      for (int index = 0; index < ROWS_PER_BATCH; index++) {
        long key = 1000L + batch * ROWS_PER_BATCH + index;
        row.putLong(0, key);
        row.position(0);
        row.limit(row.capacity());
        assertEquals(StatusCode.OK, writer.insert( 0,key, row));
      }
      assertEquals(StatusCode.OK, writer.commit(outcome));
    }
    for (int batch = 0; batch < BATCHES; batch++) {
      assertEquals(StatusCode.OK, writer.begin(IsolationLevel.REPEATABLE_READ));
      for (int index = 0; index < ROWS_PER_BATCH; index++) {
        long key = 1000L + batch * ROWS_PER_BATCH + index;
        row.putLong(0, key + 10_000);
        row.position(0);
        row.limit(row.capacity());
        assertEquals(StatusCode.OK, writer.update( 0,key, row));
      }
      assertEquals(StatusCode.OK, writer.commit(outcome));
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
        IndexedTableStore.create(directory, wal, DATABASE, GENERATION, result));
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
