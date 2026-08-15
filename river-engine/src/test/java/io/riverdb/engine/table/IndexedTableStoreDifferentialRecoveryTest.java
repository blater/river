package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
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
  void compactMutationReopensThroughPublicTablePath(@TempDir Path root)
      throws Exception {
    Fixture compact = createFixture(root.resolve("compact"));
    seed(compact.table);
    int[] operations = {
        IndexedWalCodec.MUTATION_UPDATE,
        IndexedWalCodec.MUTATION_DELETE,
        IndexedWalCodec.MUTATION_INSERT
    };
    int[] spaces = {4, 5, 5};
    long[] keys = {10, 10, 30};
    int[] previousRowIds = {1, 2, 0};
    int[] rowLengths = {Long.BYTES, 1, Long.BYTES};
    ByteBuffer rows = mutationRows();
    assertEquals(
        StatusCode.OK,
        compact.table.commitMutations(
            3,
            operations, spaces,
            keys,
            previousRowIds,
            rows,
            ROW_STRIDE,
            rowLengths,
            operations.length,
            new IndexedCommitResult()));
    assertLastOperationType(
        compact.wal, IndexedWalCodec.OPERATION_TYPE_MUTATION_BATCH);
    assertCompactState(compact.table);
    compact = crashAndReopen(compact);
    assertCompactState(compact.table);
    close(compact);
  }

  @Test
  void naturalLeafSplitPageImagesReopenThroughPublicTablePath(@TempDir Path root)
      throws Exception {
    Fixture split = createFixture(root.resolve("split"));
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    HeapInsertResult inserted = new HeapInsertResult();
    for (int key = 0; key < 256; key++) {
      row.putLong(0, key * 10L);
      row.position(0);
      row.limit(Long.BYTES);
      assertEquals(StatusCode.OK, split.table.insert(2L + key, 0, key, row, inserted));
    }
    row.putLong(0, 2560L);
    row.position(0);
    row.limit(Long.BYTES);
    assertEquals(StatusCode.OK, split.table.insert(258, 0, 256, row, inserted));
    assertLastOperationType(split.wal, IndexedWalCodec.OPERATION_TYPE_PAGE_IMAGES);
    assertSplitState(split.table);
    split = crashAndReopen(split);
    assertSplitState(split.table);
    close(split);
  }

  private static void seed(IndexedTable table) {
    int[] spaces = {4, 5};
    long[] keys = {10, 10};
    int[] rowLengths = {Long.BYTES, Long.BYTES};
    ByteBuffer rows = ByteBuffer.allocateDirect(2 * Long.BYTES);
    rows.putLong(0, 100);
    rows.putLong(Long.BYTES, 200);
    rows.position(0);
    rows.limit(rows.capacity());
    assertEquals(
        StatusCode.OK,
        table.commitInserts(
            2, spaces,
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

  private static void assertCompactState(IndexedTable table) {
    assertEquals(5, table.rowCount());
    assertEquals(2, table.obsoleteVersionCount());
    assertVisibleValue(table, 4, 10, 101);
    assertEquals(StatusCode.CONFLICT, table.fetchByKey(5, 10, new HeapRowResult()));
    assertVisibleValue(table, 5, 30, 300);
    assertCompactScan(table);
  }

  private static void assertSplitState(IndexedTable table) {
    assertEquals(257, table.rowCount());
    assertVisibleValue(table, 0, 0, 0);
    assertVisibleValue(table, 0, 127, 1270);
    assertVisibleValue(table, 0, 255, 2550);
    assertVisibleValue(table, 0, 256, 2560);
  }

  private static void assertVisibleValue(
      IndexedTable table, int space, long key, long expected) {
    HeapRowResult row = new HeapRowResult();
    assertEquals(StatusCode.OK, table.fetchByKey(space, key, row));
    ByteBuffer copied = ByteBuffer.allocate(row.length());
    assertEquals(StatusCode.OK, row.copyTo(copied));
    assertEquals(expected, copied.getLong(0));
  }

  private static void assertCompactScan(IndexedTable table) {
    IndexedScanCursor cursor = new IndexedScanCursor();
    IndexedScanResult result = new IndexedScanResult();
    assertEquals(
        StatusCode.OK,
        table.beginScan(
            table.currentCommitSequence(), 4, Long.MIN_VALUE, 6, Long.MIN_VALUE, cursor));
    assertEquals(StatusCode.OK, table.nextScan(cursor, result));
    assertEquals(4, result.keySpace());
    assertEquals(10, result.key());
    assertEquals(StatusCode.OK, table.nextScan(cursor, result));
    assertEquals(5, result.keySpace());
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
