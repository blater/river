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

final class IndexedTableStoreEncodingTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(811, 821);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final WalGeneration CHECKPOINT_GENERATION = WalGeneration.of(2);
  private static final long OPERATION_MAGIC = 0x5249564552494458L;

  @Test
  void pageImageAndCheckpointUseCurrentEncoding(@TempDir Path root) throws Exception {
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


  private static byte[] expectedPageImageHeader() {
    byte[] expected = new byte[24];
    putLong(expected, 0, OPERATION_MAGIC);
    putInt(expected, 8, IndexedWalCodec.FORMAT_VERSION);
    putInt(expected, 12, 1);
    putInt(expected, 16, 3);
    putInt(expected, 20, 0);
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
