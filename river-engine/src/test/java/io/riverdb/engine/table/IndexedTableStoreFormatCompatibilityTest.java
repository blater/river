package io.riverdb.engine.table;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.PageHeader;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import io.riverdb.wal.local.LocalWalReadResult;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexedTableStoreFormatCompatibilityTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(811, 821);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final WalGeneration CHECKPOINT_GENERATION = WalGeneration.of(2);
  private static final long OPERATION_MAGIC = 0x5249564552494458L;

  @Test
  void compactLogicalWalPayloadsRetainExactPayloadLayout(@TempDir Path root) {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTableStore store = createStore(directory, wal);
    IndexedTable table = createTable(store);

    ByteBuffer singleRow = row(0x0102030405060708L);
    assertEquals(
        StatusCode.OK,
        table.insert(2, 5, 17, singleRow, new HeapInsertResult()));

    int[] batchSpaces = {6, 7};
    long[] batchKeys = {18, 19};
    int[] batchLengths = {Long.BYTES, Long.BYTES};
    ByteBuffer batchRows = ByteBuffer.allocateDirect(2 * Long.BYTES);
    batchRows.putLong(0, 0x1112131415161718L);
    batchRows.putLong(Long.BYTES, 0x2122232425262728L);
    batchRows.position(0);
    batchRows.limit(batchRows.capacity());
    assertEquals(
        StatusCode.OK,
        table.commitInserts(
            3, batchSpaces,
            batchKeys,
            batchRows,
            Long.BYTES,
            batchLengths,
            batchKeys.length,
            new IndexedCommitResult()));

    int[] mutations = {IndexedWalCodec.MUTATION_UPDATE, IndexedWalCodec.MUTATION_DELETE};
    int[] mutationSpaces = {5, 6};
    long[] mutationKeys = {17, 18};
    int[] previousRowIds = {1, 2};
    int[] mutationLengths = {Long.BYTES, 1};
    ByteBuffer mutationRows = ByteBuffer.allocateDirect(2 * Long.BYTES);
    mutationRows.putLong(0, 0x3132333435363738L);
    mutationRows.put(Long.BYTES, (byte) 0x41);
    mutationRows.position(0);
    mutationRows.limit(Long.BYTES + 1);
    assertEquals(
        StatusCode.OK,
        table.commitMutations(
            4,
            mutations, mutationSpaces,
            mutationKeys,
            previousRowIds,
            mutationRows,
            Long.BYTES,
            mutationLengths,
            mutations.length,
            new IndexedCommitResult()));

    LocalWalReadResult record = new LocalWalReadResult();
    long offset = WalFileHeaderCodec.HEADER_BYTES;
    assertEquals(StatusCode.OK, wal.read(offset, record));
    assertRecordHeader(record, 1, 1);
    offset = record.nextOffset();

    assertEquals(StatusCode.OK, wal.read(offset, record));
    assertRecordHeader(record, 2, 2);
    assertArrayEquals(expectedSingleInsert(), bytes(record.payload()));
    offset = record.nextOffset();

    assertEquals(StatusCode.OK, wal.read(offset, record));
    assertRecordHeader(record, 3, 3);
    assertArrayEquals(expectedInsertBatch(), bytes(record.payload()));
    offset = record.nextOffset();

    assertEquals(StatusCode.OK, wal.read(offset, record));
    assertRecordHeader(record, 4, 4);
    assertArrayEquals(expectedMutationBatch(), bytes(record.payload()));
    assertEquals(record.nextOffset(), wal.tailEnd());

    close(table, wal, directory);
  }

  @Test
  void pageImageAndCheckpointBytesRetainGoldenEncoding(@TempDir Path root) throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = openWal(directory);
    IndexedTableStore store = createStore(directory, wal);
    IndexedTable table = createTable(store);

    LocalWalReadResult bootstrap = new LocalWalReadResult();
    assertEquals(
        StatusCode.OK,
        wal.read(WalFileHeaderCodec.HEADER_BYTES, bootstrap));
    assertRecordHeader(bootstrap, 1, 1);
    byte[] bootstrapPayload = bytes(bootstrap.payload());
    assertArrayEquals(expectedPageImageHeader(), Arrays.copyOf(bootstrapPayload, 24));
    assertEquals(24 + 3 * PageCodec.PAGE_BYTES, bootstrapPayload.length);
    assertPageImageHeaders(
        bootstrapPayload,
        WalFileHeaderCodec.HEADER_BYTES,
        bootstrap.nextOffset());

    byte[] flushedPages = Files.readAllBytes(root.resolve(IndexedTableStore.FILE_NAME));
    assertEquals(3 * PageCodec.PAGE_BYTES, flushedPages.length);
    for (int pageIndex = 0; pageIndex < 3; pageIndex++) {
      int imageOffset = 24 + pageIndex * PageCodec.PAGE_BYTES;
      int fileOffset = pageIndex * PageCodec.PAGE_BYTES;
      assertArrayEquals(
          Arrays.copyOfRange(
              bootstrapPayload,
              imageOffset,
              imageOffset + PageCodec.PAGE_BYTES),
          Arrays.copyOfRange(
              flushedPages,
              fileOffset,
              fileOffset + PageCodec.PAGE_BYTES));
    }

    assertEquals(StatusCode.OK, store.rebaseForCheckpoint(CHECKPOINT_GENERATION));
    Path checkpoint = root.resolve(
        IndexedTableStore.checkpointFileName(CHECKPOINT_GENERATION));
    byte[] checkpointBytes = Files.readAllBytes(checkpoint);
    assertCheckpointHeaders(checkpointBytes, table.pageCount());
    for (int pageIndex = 0; pageIndex < 3; pageIndex++) {
      int fileOffset = pageIndex * PageCodec.PAGE_BYTES;
      assertArrayEquals(
          Arrays.copyOfRange(
              flushedPages,
              fileOffset + PageCodec.HEADER_BYTES,
              fileOffset + PageCodec.PAGE_BYTES),
          Arrays.copyOfRange(
              checkpointBytes,
              fileOffset + PageCodec.HEADER_BYTES,
              fileOffset + PageCodec.PAGE_BYTES));
    }

    assertEquals(StatusCode.OK, table.close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  private static byte[] expectedSingleInsert() {
    byte[] expected = new byte[48];
    putLong(expected, 0, OPERATION_MAGIC);
    putInt(expected, 8, IndexedWalCodec.FORMAT_VERSION);
    putInt(expected, 12, 2);
    putLong(expected, 16, 17);
    putInt(expected, 24, 1);
    putInt(expected, 28, Long.BYTES);
    putInt(expected, 32, 5);
    putInt(expected, 36, 0);
    putBigEndianLong(expected, 40, 0x0102030405060708L);
    return expected;
  }

  private static byte[] expectedPageImageHeader() {
    byte[] expected = new byte[24];
    putLong(expected, 0, OPERATION_MAGIC);
    putInt(expected, 8, IndexedWalCodec.FORMAT_VERSION);
    putInt(expected, 12, 1);
    putInt(expected, 16, 3);
    putInt(expected, 20, 0);
    return expected;
  }

  private static byte[] expectedInsertBatch() {
    byte[] expected = new byte[80];
    putLong(expected, 0, OPERATION_MAGIC);
    putInt(expected, 8, IndexedWalCodec.FORMAT_VERSION);
    putInt(expected, 12, 3);
    putInt(expected, 16, 2);
    putInt(expected, 20, 0);
    putLong(expected, 24, 18);
    putInt(expected, 32, 2);
    putInt(expected, 36, Long.BYTES);
    putInt(expected, 40, 6);
    putBigEndianLong(expected, 44, 0x1112131415161718L);
    putLong(expected, 52, 19);
    putInt(expected, 60, 3);
    putInt(expected, 64, Long.BYTES);
    putInt(expected, 68, 7);
    putBigEndianLong(expected, 72, 0x2122232425262728L);
    return expected;
  }

  private static byte[] expectedMutationBatch() {
    byte[] expected = new byte[89];
    putLong(expected, 0, OPERATION_MAGIC);
    putInt(expected, 8, IndexedWalCodec.FORMAT_VERSION);
    putInt(expected, 12, 4);
    putInt(expected, 16, 2);
    putInt(expected, 20, 0);
    putInt(expected, 24, IndexedWalCodec.MUTATION_UPDATE);
    putLong(expected, 28, 17);
    putInt(expected, 36, 4);
    putInt(expected, 40, 1);
    putInt(expected, 44, Long.BYTES);
    putInt(expected, 48, 5);
    putBigEndianLong(expected, 52, 0x3132333435363738L);
    putInt(expected, 60, IndexedWalCodec.MUTATION_DELETE);
    putLong(expected, 64, 18);
    putInt(expected, 72, 5);
    putInt(expected, 76, 2);
    putInt(expected, 80, 1);
    putInt(expected, 84, 6);
    expected[88] = 0x41;
    return expected;
  }

  private static void assertRecordHeader(
      LocalWalReadResult record,
      long transactionId,
      long commitSequence) {
    assertEquals(transactionId, record.header().transactionId());
    assertEquals(commitSequence, record.header().commitSequence());
    assertEquals(1, record.header().decisionCode());
    assertEquals(1002, record.header().formatId());
    assertEquals(IndexedWalCodec.FORMAT_VERSION, record.header().formatVersion());
  }

  private static void assertCheckpointHeaders(byte[] encoded, int pageCount) {
    assertEquals((long) pageCount * PageCodec.PAGE_BYTES, encoded.length);
    CRC32C checksum = new CRC32C();
    PageHeader header = new PageHeader();
    ByteBuffer pages = ByteBuffer.wrap(encoded);
    for (int pageId = 1; pageId <= pageCount; pageId++) {
      int offset = (pageId - 1) * PageCodec.PAGE_BYTES;
      pages.position(offset);
      pages.limit(offset + PageCodec.PAGE_BYTES);
      ByteBuffer page = pages.slice();
      assertEquals(StatusCode.OK, PageCodec.validate(page, header, checksum));
      assertEquals(DATABASE.high(), header.databaseHigh());
      assertEquals(DATABASE.low(), header.databaseLow());
      assertEquals(CHECKPOINT_GENERATION.value(), header.walGeneration());
      assertEquals(pageId, header.pageId());
      assertEquals(1, header.pageGeneration());
      assertEquals(0, header.recordStart());
      assertEquals(0, header.recordEnd());
      pages.clear();
    }
  }

  private static void assertPageImageHeaders(
      byte[] encoded,
      long recordStart,
      long recordEnd) {
    CRC32C checksum = new CRC32C();
    PageHeader header = new PageHeader();
    ByteBuffer payload = ByteBuffer.wrap(encoded);
    for (int pageId = 1; pageId <= 3; pageId++) {
      int offset = 24 + (pageId - 1) * PageCodec.PAGE_BYTES;
      assertEquals(
          StatusCode.OK,
          PageCodec.validateAt(payload, offset, header, checksum));
      assertEquals(DATABASE.high(), header.databaseHigh());
      assertEquals(DATABASE.low(), header.databaseLow());
      assertEquals(GENERATION.value(), header.walGeneration());
      assertEquals(pageId, header.pageId());
      assertEquals(1, header.pageGeneration());
      assertEquals(recordStart, header.recordStart());
      assertEquals(recordEnd, header.recordEnd());
      assertEquals(PageCodec.MAX_PAYLOAD_BYTES, header.payloadBytes());
    }
  }

  private static byte[] bytes(ByteBuffer source) {
    ByteBuffer copy = source.duplicate();
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    return bytes;
  }

  private static void putInt(byte[] target, int offset, int value) {
    for (int index = 0; index < Integer.BYTES; index++) {
      target[offset + index] = (byte) (value >>> (index * Byte.SIZE));
    }
  }

  private static void putLong(byte[] target, int offset, long value) {
    for (int index = 0; index < Long.BYTES; index++) {
      target[offset + index] = (byte) (value >>> (index * Byte.SIZE));
    }
  }

  private static void putBigEndianLong(byte[] target, int offset, long value) {
    for (int index = 0; index < Long.BYTES; index++) {
      target[offset + index] =
          (byte) (value >>> ((Long.BYTES - index - 1) * Byte.SIZE));
    }
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
