package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.wal.WalFileHeaderCodec;
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
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedTableStoreDifferentialRecoveryTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(853, 857);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final int ROW_STRIDE = Long.BYTES;

  @Test
  void logicalMutationReopensThroughTransactionPath(@TempDir Path root)
      throws Exception {
    Fixture compact = createFixture(root.resolve("compact"));
    seed(compact.table);
    TransactionWriter writer = new TransactionWriter(compact.table, ROW_STRIDE);
    assertEquals(StatusCode.OK, writer.session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.session.update(4, 10, row(101)));
    assertEquals(StatusCode.OK, writer.session.delete(5, 10));
    assertEquals(StatusCode.OK, writer.session.insert(5, 30, row(300)));
    assertEquals(StatusCode.OK, writer.session.commit(writer.outcome));
    assertEquals(StatusCode.OK, writer.session.close());
    assertLastRelationalRecord(compact.wal);
    assertCompactState(compact.table);
    compact = crashAndReopen(compact);
    assertCompactState(compact.table);
    close(compact);
  }

  @Test
  void naturalLeafSplitReopensThroughTransactionPath(@TempDir Path root)
      throws Exception {
    Fixture split = createFixture(root.resolve("split"));
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    TransactionWriter writer = new TransactionWriter(split.table, Long.BYTES);
    for (int key = 0; key < 256; key++) {
      row.putLong(0, key * 10L);
      row.position(0);
      row.limit(Long.BYTES);
      writer.insert(0, key, row);
    }
    row.putLong(0, 2560L);
    row.position(0);
    row.limit(Long.BYTES);
    writer.insert(0, 256, row);
    assertEquals(StatusCode.OK, writer.session.close());
    assertLastRelationalRecord(split.wal);
    assertSplitState(split.table);
    split = crashAndReopen(split);
    assertSplitState(split.table);
    close(split);
  }

  @Test
  void longMaximumSpaceSurvivesWalRecoveryAndOrderedScan(@TempDir Path root)
      throws Exception {
    Fixture fixture = createFixture(root.resolve("long-space"));
    TransactionWriter writer = new TransactionWriter(fixture.table, Long.BYTES);
    writer.insert(Long.MAX_VALUE, 41, row(901));
    assertEquals(StatusCode.OK, writer.session.close());
    fixture = crashAndReopen(fixture);
    assertVisibleValue(fixture.table, Long.MAX_VALUE, 41, 901);
    IndexedScanCursor cursor = new IndexedScanCursor();
    IndexedScanResult result = new IndexedScanResult();
    assertEquals(StatusCode.OK, fixture.table.beginScan(
        fixture.table.currentCommitSequence(), Long.MAX_VALUE, Long.MIN_VALUE,
        OrderedKey.INFINITY_SPACE, 0, cursor));
    assertEquals(StatusCode.OK, fixture.table.nextScan(cursor, result));
    assertEquals(Long.MAX_VALUE, result.keySpace());
    assertEquals(41, result.key());
    assertEquals(StatusCode.CONFLICT, fixture.table.nextScan(cursor, result));
    assertEquals(StatusCode.OK, fixture.table.closeScan(cursor));
    close(fixture);
  }

  private static void seed(IndexedTable table) {
    TransactionWriter writer = new TransactionWriter(table, ROW_STRIDE);
    assertEquals(StatusCode.OK, writer.session.begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(StatusCode.OK, writer.session.insert(4, 10, row(100)));
    assertEquals(StatusCode.OK, writer.session.insert(5, 10, row(200)));
    assertEquals(StatusCode.OK, writer.session.commit(writer.outcome));
    assertEquals(StatusCode.OK, writer.session.close());
  }

  private static ByteBuffer row(long value) {
    ByteBuffer row = ByteBuffer.allocateDirect(Long.BYTES);
    row.putLong(0, value);
    row.position(0);
    row.limit(Long.BYTES);
    return row;
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
      IndexedTable table, long space, long key, long expected) {
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

  private static void assertLastRelationalRecord(LocalWal wal) {
    long offset = WalFileHeaderCodec.HEADER_BYTES;
    LocalWalReadResult record = new LocalWalReadResult();
    while (offset < wal.tailEnd()) {
      assertEquals(StatusCode.OK, wal.read(offset, record));
      offset = record.nextOffset();
    }
    assertEquals(IndexedRelationalWalCodec.WAL_FORMAT_ID, record.header().formatId());
    assertEquals(IndexedRelationalWalCodec.WAL_FORMAT_VERSION, record.header().formatVersion());
    assertEquals(IndexedRelationalWalCodec.MAGIC, FormatBytes.getLong(record.payload(), 0));
    assertEquals(IndexedRelationalWalCodec.VERSION, FormatBytes.getInt(record.payload(), 8));
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

  private static final class TransactionWriter {
    private final IndexedTransactionSession session;
    private final TransactionOutcome outcome = new TransactionOutcome();

    private TransactionWriter(IndexedTable table, int maximumRowBytes) {
      TransactionManager manager = new TransactionManager(
          DATABASE.high(), DATABASE.low(), table.nextTransactionId(), 4);
      session = new IndexedTransactionSession(manager, table, maximumRowBytes);
    }

    private void insert(long space, long key, ByteBuffer value) {
      assertEquals(StatusCode.OK, session.begin(IsolationLevel.REPEATABLE_READ));
      assertEquals(StatusCode.OK, session.insert(space, key, value));
      assertEquals(StatusCode.OK, session.commit(outcome));
    }
  }
}
