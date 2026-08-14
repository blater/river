package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedTableStoreLifecycleTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(829, 839);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void preservesIdleStagedPreparedForcedAndClosedStatuses(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTableStore store = createStore(directory, wal);
    createTable(store);

    assertEquals(StatusCode.CONFLICT, store.cancelOperation());
    assertEquals(StatusCode.CONFLICT, store.cancelPreparedInsertPreflight());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, store.forcePreparedInserts());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, store.publishForcedInserts());

    assertEquals(StatusCode.OK, store.beginOperation());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, store.beginOperation());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, store.beginPreparedInsertGroup());
    assertEquals(StatusCode.OK, store.flush());
    assertEquals(StatusCode.CONFLICT, store.close());
    assertEquals(StatusCode.OK, store.cancelOperation());

    ByteBuffer rows = row(410);
    HeapInsertResult inserted = new HeapInsertResult();
    PendingMutationBuffer mutations = new PendingMutationBuffer(1, Long.BYTES);
    mutations.append(IndexedWalCodec.MUTATION_INSERT, 41, 0, rows, 0, Long.BYTES);
    assertEquals(StatusCode.OK, store.beginPreparedInsertGroup());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, store.beginOperation());
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, store.beginPreparedInsertGroup());
    assertEquals(StatusCode.RETRY, store.flush());
    assertEquals(StatusCode.CONFLICT, store.close());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, store.forcePreparedInserts());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, store.publishForcedInserts());
    assertEquals(StatusCode.OK, store.preflightPreparedWrites(mutations));
    assertEquals(StatusCode.OK, store.finishPreparedInsertPreflight(1));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, store.forcePreparedInserts());
    assertEquals(StatusCode.OK, store.cancelPreparedInsertPreflight());

    assertEquals(StatusCode.OK, store.beginPreparedInsertGroup());
    assertEquals(StatusCode.OK, store.preflightPreparedWrites(mutations));
    assertEquals(StatusCode.OK, store.finishPreparedInsertPreflight(1));
    assertEquals(
        StatusCode.OK,
        store.appendPreparedWrites(2, 2, mutations, inserted));
    assertEquals(StatusCode.CONFLICT, store.cancelPreparedInsertPreflight());
    assertEquals(StatusCode.CONFLICT, store.close());
    assertEquals(StatusCode.RETRY, store.flush());
    assertEquals(StatusCode.OK, store.forcePreparedInserts());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, store.forcePreparedInserts());
    assertEquals(StatusCode.CONFLICT, store.close());
    assertEquals(StatusCode.OK, store.publishForcedInserts());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, store.publishForcedInserts());
    assertEquals(StatusCode.CONFLICT, store.close());
    assertEquals(StatusCode.OK, store.flush());
    assertEquals(StatusCode.OK, store.close());

    assertEquals(StatusCode.CLOSED, store.close());
    assertEquals(StatusCode.CLOSED, store.beginOperation());
    assertEquals(StatusCode.CLOSED, store.beginPreparedInsertGroup());
    assertEquals(StatusCode.CLOSED, store.flush());
    assertEquals(StatusCode.CONFLICT, store.cancelOperation());
    assertEquals(StatusCode.CONFLICT, store.cancelPreparedInsertPreflight());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, store.forcePreparedInserts());
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, store.publishForcedInserts());

    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void preparedPublishFailurePoisonsAdmissionWithoutChangingClosePrecedence(
      @TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTableStore store = createStore(directory, wal);
    createTable(store);

    ByteBuffer firstRow = row(510);
    ByteBuffer duplicateRow = row(511);
    HeapInsertResult inserted = new HeapInsertResult();
    PendingMutationBuffer first = new PendingMutationBuffer(1, Long.BYTES);
    first.append(IndexedWalCodec.MUTATION_INSERT, 51, 0, firstRow, 0, Long.BYTES);
    PendingMutationBuffer duplicate = new PendingMutationBuffer(1, Long.BYTES);
    duplicate.append(IndexedWalCodec.MUTATION_INSERT, 51, 0, duplicateRow, 0, Long.BYTES);

    assertEquals(StatusCode.OK, store.beginPreparedInsertGroup());
    assertEquals(StatusCode.OK, store.preflightPreparedWrites(first));
    PendingMutationBuffer secondPreflight = new PendingMutationBuffer(1, Long.BYTES);
    ByteBuffer secondRow = row(520);
    secondPreflight.append(
        IndexedWalCodec.MUTATION_INSERT, 52, 0, secondRow, 0, Long.BYTES);
    assertEquals(StatusCode.OK, store.preflightPreparedWrites(secondPreflight));
    assertEquals(StatusCode.OK, store.finishPreparedInsertPreflight(2));
    assertEquals(
        StatusCode.OK,
        store.appendPreparedWrites(2, 2, first, inserted));
    assertEquals(
        StatusCode.OK,
        store.appendPreparedWrites(3, 3, duplicate, inserted));
    assertEquals(StatusCode.OK, store.forcePreparedInserts());
    assertEquals(StatusCode.CORRUPTION, store.publishForcedInserts());

    assertEquals(StatusCode.FENCED, store.beginOperation());
    assertEquals(StatusCode.FENCED, store.beginPreparedInsertGroup());
    assertEquals(StatusCode.FENCED, store.flush());
    assertEquals(StatusCode.CONFLICT, store.close());
    assertEquals(StatusCode.CONFLICT, store.cancelPreparedInsertPreflight());

    assertEquals(StatusCode.OK, directory.close());
  }

  private static ByteBuffer row(long value) {
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    row.putLong(0, value);
    row.position(0);
    row.limit(Long.BYTES);
    return row;
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
}
