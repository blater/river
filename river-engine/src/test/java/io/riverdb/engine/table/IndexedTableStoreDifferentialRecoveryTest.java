package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.btree.BTreeLookupResult;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import io.riverdb.wal.local.LocalWalReadResult;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedTableStoreDifferentialRecoveryTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(853, 857);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final int ROW_STRIDE = Long.BYTES;

  @Test
  void compactLogicalAndPageImageMutationReopenToEquivalentState(@TempDir Path root)
      throws Exception {
    Fixture compact = createFixture(root.resolve("compact"));
    Fixture pageImage = createFixture(root.resolve("page-image"));
    seed(compact.table);
    seed(pageImage.table);

    int[] operations = {
        IndexedWalCodec.MUTATION_UPDATE,
        IndexedWalCodec.MUTATION_DELETE,
        IndexedWalCodec.MUTATION_INSERT
    };
    long[] keys = {10, 20, 30};
    int[] previousRowIds = {1, 2, 0};
    int[] rowLengths = {Long.BYTES, 1, Long.BYTES};
    ByteBuffer rows = mutationRows();
    assertEquals(
        StatusCode.OK,
        compact.table.commitMutations(
            3,
            operations,
            keys,
            previousRowIds,
            rows,
            ROW_STRIDE,
            rowLengths,
            operations.length,
            new IndexedCommitResult()));
    assertEquals(
        StatusCode.OK,
        commitPageImageMutations(
            pageImage.store,
            3,
            operations,
            keys,
            previousRowIds,
            mutationRows(),
            ROW_STRIDE,
            rowLengths));
    assertLastOperationType(
        compact.wal, IndexedWalCodec.OPERATION_TYPE_MUTATION_BATCH);
    assertLastOperationType(
        pageImage.wal, IndexedWalCodec.OPERATION_TYPE_PAGE_IMAGES);

    assertEquivalent(compact, pageImage);
    compact = crashAndReopen(compact);
    pageImage = crashAndReopen(pageImage);
    assertEquivalent(compact, pageImage);

    close(compact);
    close(pageImage);
  }

  private static StatusCode commitPageImageMutations(
      IndexedTableStore store,
      long transactionId,
      int[] operations,
      long[] keys,
      int[] previousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths) {
    StatusCode status = store.beginOperation();
    if (!status.isOk()) {
      return status;
    }
    IndexedPageSet pages = store.pages();
    IndexedTableKernel kernel = store.kernel();
    HeapInsertResult inserted = new HeapInsertResult();
    BTreeLookupResult lookup = new BTreeLookupResult();
    for (int index = 0; index < operations.length; index++) {
      int leafPageId = kernel.findLeafPageId(keys[index]);
      ByteBuffer leaf = pages.stageExisting(
          leafPageId, IndexedTableStore.MAX_CHANGED_PAGES);
      if (leaf == null) {
        store.cancelOperation();
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      status = BTreePage.lookupLeaf(leaf, keys[index], lookup);
      boolean newEntry = operations[index] == IndexedWalCodec.MUTATION_INSERT
          && previousRowIds[index] == 0;
      if ((newEntry && status != StatusCode.CONFLICT)
          || (!newEntry
              && (!status.isOk() || lookup.rowId() != previousRowIds[index]))) {
        store.cancelOperation();
        return StatusCode.CORRUPTION;
      }
      status = kernel.stageVersionRow(
          rows,
          index * rowStride,
          rowLengths[index],
          previousRowIds[index],
          operations[index] == IndexedWalCodec.MUTATION_DELETE,
          inserted);
      if (status.isOk()) {
        status = newEntry
            ? BTreePage.insertLeaf(leaf, keys[index], inserted.rowId())
            : BTreePage.updateLeaf(leaf, keys[index], inserted.rowId());
      }
      if (!status.isOk()) {
        store.cancelOperation();
        return status;
      }
    }
    return store.commit(transactionId, store.nextCommitSequence());
  }

  private static void seed(IndexedTable table) {
    long[] keys = {10, 20};
    int[] rowLengths = {Long.BYTES, Long.BYTES};
    ByteBuffer rows = ByteBuffer.allocateDirect(2 * Long.BYTES);
    rows.putLong(0, 100);
    rows.putLong(Long.BYTES, 200);
    rows.position(0);
    rows.limit(rows.capacity());
    assertEquals(
        StatusCode.OK,
        table.commitInserts(
            2,
            keys,
            rows,
            ROW_STRIDE,
            rowLengths,
            keys.length,
            new IndexedCommitResult()));
  }

  private static ByteBuffer mutationRows() {
    ByteBuffer rows = ByteBuffer.allocateDirect(3 * ROW_STRIDE);
    rows.putLong(0, 101);
    rows.put(ROW_STRIDE, (byte) 1);
    rows.putLong(2 * ROW_STRIDE, 300);
    rows.position(0);
    rows.limit(rows.capacity());
    return rows;
  }

  private static void assertEquivalent(Fixture first, Fixture second) {
    assertEquals(first.table.rowCount(), second.table.rowCount());
    assertEquals(first.table.obsoleteVersionCount(), second.table.obsoleteVersionCount());
    assertEquals(first.table.currentCommitSequence(), second.table.currentCommitSequence());
    assertEquals(first.table.nextCommitSequence(), second.table.nextCommitSequence());
    assertEquals(first.table.nextTransactionId(), second.table.nextTransactionId());
    for (int rowId = 1; rowId <= first.table.rowCount(); rowId++) {
      assertEquals(
          first.store.rowCommitSequence(rowId), second.store.rowCommitSequence(rowId));
      assertEquals(first.store.previousRowId(rowId), second.store.previousRowId(rowId));
      assertEquals(first.store.isDeletedRow(rowId), second.store.isDeletedRow(rowId));
      assertArrayEquals(rowBytes(first.store, rowId), rowBytes(second.store, rowId));
    }
    assertVisibleValue(first.table, 10, 101);
    assertVisibleValue(second.table, 10, 101);
    assertEquals(StatusCode.CONFLICT, first.table.fetchByKey(20, new HeapRowResult()));
    assertEquals(StatusCode.CONFLICT, second.table.fetchByKey(20, new HeapRowResult()));
    assertVisibleValue(first.table, 30, 300);
    assertVisibleValue(second.table, 30, 300);
    assertScan(first.table);
    assertScan(second.table);
  }

  private static byte[] rowBytes(IndexedTableStore store, int rowId) {
    HeapRowResult row = new HeapRowResult();
    assertEquals(StatusCode.OK, store.fetchRow(rowId, row));
    ByteBuffer copied = ByteBuffer.allocate(row.length());
    assertEquals(StatusCode.OK, row.copyTo(copied));
    byte[] bytes = new byte[row.length()];
    copied.position(0);
    copied.get(bytes);
    return bytes;
  }

  private static void assertVisibleValue(IndexedTable table, long key, long expected) {
    HeapRowResult row = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(key, row));
    ByteBuffer copied = ByteBuffer.allocate(row.length());
    assertEquals(StatusCode.OK, row.copyTo(copied));
    assertEquals(expected, copied.getLong(0));
  }

  private static void assertScan(IndexedTable table) {
    IndexedScanCursor cursor = new IndexedScanCursor();
    IndexedScanResult result = new IndexedScanResult();
    assertEquals(
        StatusCode.OK,
        table.beginScan(table.currentCommitSequence(), Long.MIN_VALUE, Long.MAX_VALUE, cursor));
    assertEquals(StatusCode.OK, table.nextScan(cursor, result));
    assertEquals(10, result.key());
    assertEquals(StatusCode.OK, table.nextScan(cursor, result));
    assertEquals(30, result.key());
    assertEquals(StatusCode.CONFLICT, table.nextScan(cursor, result));
    assertEquals(StatusCode.OK, table.closeScan(cursor));
  }

  private static void assertLastOperationType(LocalWal wal, int expectedType) {
    long offset = WalFileHeaderCodec.HEADER_BYTES;
    LocalWalReadResult record = new LocalWalReadResult();
    while (offset < wal.tailEnd()) {
      assertEquals(StatusCode.OK, wal.read(offset, record));
      offset = record.nextOffset();
    }
    assertEquals(expectedType, IndexedWalCodec.operationType(record.payload()));
  }

  private static Fixture createFixture(Path root) throws Exception {
    Files.createDirectory(root);
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.create(directory, wal, DATABASE, GENERATION, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(storeResult.store(), tableResult));
    return new Fixture(root, directory, wal, storeResult.store(), tableResult.table());
  }

  private static Fixture crashAndReopen(Fixture fixture) {
    assertEquals(StatusCode.OK, fixture.directory.advanceGeneration());
    assertEquals(StatusCode.OK, fixture.directory.close());
    NioDurableDirectory directory = openDirectory(fixture.root);
    LocalWal wal = openWal(directory);
    IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.open(directory, wal, DATABASE, GENERATION, storeResult));
    IndexedTableOpenResult tableResult = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.open(storeResult.store(), tableResult));
    return new Fixture(fixture.root, directory, wal, storeResult.store(), tableResult.table());
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

  private static void close(Fixture fixture) {
    assertEquals(StatusCode.OK, fixture.table.flush());
    assertEquals(StatusCode.OK, fixture.table.close());
    assertEquals(StatusCode.OK, fixture.wal.close());
    assertEquals(StatusCode.OK, fixture.directory.close());
  }

  private record Fixture(
      Path root,
      NioDurableDirectory directory,
      LocalWal wal,
      IndexedTableStore store,
      IndexedTable table) {
  }
}
