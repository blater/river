package io.riverdb.engine.table;

import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.PageHeader;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalAppendResult;
import io.riverdb.wal.local.LocalWalOpenResult;
import io.riverdb.wal.local.LocalWalReservation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class IndexedFreePageDurableRecoveryTest {
  private static final DatabaseIncarnation DATABASE = DatabaseIncarnation.of(941, 947);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final int FIRST_FREE_PAGE_ID = 4;
  private static final int LAST_FREE_PAGE_ID = 6;
  private static final int PAGE_COUNT = 4;
  private static final int CHECKSUM_OFFSET = 120;
  private static final int CHECKSUM_COMPLEMENT_OFFSET = 124;

  @ParameterizedTest(name = "{0}")
  @EnumSource(FreeStackCorruption.class)
  void openExistingRejectsChecksumValidFreeStackCorruption(
      FreeStackCorruption corruption,
      @TempDir Path temporaryDirectory) throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve(corruption.name()));
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = createWal(directory);
    IndexedTableStore store = createStore(directory, wal);
    createTable(store);
    byte[][] basePages = readBasePages(root);
    assertEquals(StatusCode.OK, store.close());

    appendFreeStackRecord(wal, basePages, corruption);
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());

    directory = openDirectory(root);
    wal = openExistingWal(directory);
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.CORRUPTION,
        IndexedTableStore.openExisting(
            directory, wal, DATABASE, GENERATION, databaseProviderLease(1), reopened),
        corruption.name());
    assertNull(reopened.store(), corruption.name());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  @Test
  void tornReclaimPageBatchIsDiscardedBeforeTableReplay(@TempDir Path root)
      throws Exception {
    NioDurableDirectory directory = openDirectory(root);
    LocalWal wal = createWal(directory);
    IndexedTableStore store = createStore(directory, wal);
    createTable(store);
    byte[][] basePages = readBasePages(root);
    assertEquals(StatusCode.OK, store.close());

    long recordStart = appendFreeStackRecord(wal, basePages, null);
    long recordEnd = wal.tailEnd();
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
    truncate(root.resolve(LocalWal.FILE_NAME), recordStart + (recordEnd - recordStart) / 2);

    directory = openDirectory(root);
    wal = openExistingWal(directory);
    assertEquals(recordStart, wal.tailEnd());
    IndexedTableStoreOpenResult reopened = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.openExisting(
            directory, wal, DATABASE, GENERATION, databaseProviderLease(1), reopened));
    assertNotNull(reopened.store());
    assertEquals(3, reopened.store().pageCount());
    assertEquals(StatusCode.OK, reopened.store().validate());
    assertEquals(StatusCode.OK, reopened.store().close());
    assertEquals(StatusCode.OK, wal.close());
    assertEquals(StatusCode.OK, directory.close());
  }

  private static long appendFreeStackRecord(
      LocalWal wal,
      byte[][] basePages,
      FreeStackCorruption corruption) {
    int operationBytes = IndexedWalCodec.pageOperationBytes(PAGE_COUNT, 0);
    LocalWalReservation reservation = new LocalWalReservation();
    assertEquals(StatusCode.OK, wal.reserve(operationBytes, reservation));
    long recordStart = reservation.recordStartOffset();
    long recordEnd = reservation.recordEndOffset();

    ByteBuffer root = ByteBuffer.wrap(basePages[1].clone());
    ByteBuffer leaf = ByteBuffer.wrap(basePages[2].clone());
    ByteBuffer[] freePages = new ByteBuffer[LAST_FREE_PAGE_ID + 1];
    for (int pageId = FIRST_FREE_PAGE_ID; pageId <= LAST_FREE_PAGE_ID; pageId++) {
      freePages[pageId] = ByteBuffer.allocate(PageCodec.PAGE_BYTES);
    }
    initializeCanonicalStack(root, freePages);
    if (corruption != null) corruption.apply(root, leaf, freePages);

    encodeExisting(root, 2, PageCodec.PAYLOAD_KIND_SCALAR_BTREE, 0,
        PageCodec.MAX_PAYLOAD_BYTES, recordStart, recordEnd);
    for (int pageId = FIRST_FREE_PAGE_ID; pageId <= LAST_FREE_PAGE_ID; pageId++) {
      ByteBuffer page = freePages[pageId];
      if (corruption == FreeStackCorruption.CHAIN_MEMBER_NON_FREE && pageId == 5) {
        page = leaf;
        freePages[pageId] = page;
        encodeExisting(page, pageId, PageCodec.PAYLOAD_KIND_SCALAR_BTREE, 0,
            PageCodec.MAX_PAYLOAD_BYTES, recordStart, recordEnd);
      } else {
        encodeExisting(page, pageId, PageCodec.PAYLOAD_KIND_FREE, 0,
            PageCodec.FREE_PAYLOAD_BYTES, recordStart, recordEnd);
      }
    }
    if (corruption == FreeStackCorruption.FREE_PAGE_WRONG_OWNER) {
      FormatBytes.putLong(freePages[5], 88, 1_000);
      refreshChecksum(freePages[5]);
    } else if (corruption == FreeStackCorruption.NONZERO_FREE_REMAINDER) {
      freePages[5].put(PageCodec.HEADER_BYTES + PageCodec.FREE_PAYLOAD_BYTES, (byte) 1);
      refreshChecksum(freePages[5]);
    }

    ByteBuffer payload = reservation.writablePayload();
    IndexedWalCodec.encodePageOperationHeader(payload, PAGE_COUNT, 0);
    copyPage(root, payload, IndexedWalCodec.PAGE_OPERATION_HEADER_BYTES);
    int outputOffset = IndexedWalCodec.PAGE_OPERATION_HEADER_BYTES + PageCodec.PAGE_BYTES;
    for (int pageId = FIRST_FREE_PAGE_ID; pageId <= LAST_FREE_PAGE_ID; pageId++) {
      copyPage(freePages[pageId], payload, outputOffset);
      outputOffset += PageCodec.PAGE_BYTES;
    }
    payload.position(operationBytes);
    LocalWalAppendResult append = new LocalWalAppendResult();
    assertEquals(
        StatusCode.OK,
        wal.publish(
            reservation,
            2,
            2,
            1,
            IndexedTableStore.WAL_FORMAT_ID,
            IndexedTableStore.WAL_FORMAT_VERSION,
            append));
    assertEquals(recordStart, append.startOffset());
    assertEquals(recordEnd, append.endOffset());
    return recordStart;
  }

  private static void initializeCanonicalStack(
      ByteBuffer rootPage,
      ByteBuffer[] freePages) {
    ByteBuffer metadata = payload(rootPage);
    for (int pageId = FIRST_FREE_PAGE_ID; pageId <= LAST_FREE_PAGE_ID; pageId++) {
      assertEquals(StatusCode.OK, BTreeRootPage.allocatePage(metadata, pageId, -1));
    }
    for (int pageId = FIRST_FREE_PAGE_ID; pageId <= LAST_FREE_PAGE_ID; pageId++) {
      assertEquals(
          StatusCode.OK,
          BTreeRootPage.releasePage(metadata, pageId, payload(freePages[pageId])));
    }
    assertEquals(LAST_FREE_PAGE_ID, BTreeRootPage.freePageHead(metadata));
    assertEquals(3, BTreeRootPage.freePageCount(metadata));
  }

  private static void encodeExisting(
      ByteBuffer page,
      int pageId,
      int payloadKind,
      long ownerKeyId,
      int payloadBytes,
      long recordStart,
      long recordEnd) {
    assertEquals(
        StatusCode.OK,
        PageCodec.encode(
            DATABASE,
            GENERATION,
            pageId,
            1,
            recordStart,
            recordEnd,
            payloadKind,
            ownerKeyId,
            payloadBytes,
            page,
            new CRC32C()));
  }

  private static byte[][] readBasePages(Path root) throws IOException {
    byte[] encoded = Files.readAllBytes(root.resolve(IndexedTableStore.FILE_NAME));
    assertEquals(3 * PageCodec.PAGE_BYTES, encoded.length);
    byte[][] pages = new byte[3][PageCodec.PAGE_BYTES];
    for (int index = 0; index < pages.length; index++) {
      System.arraycopy(encoded, index * PageCodec.PAGE_BYTES, pages[index], 0, PageCodec.PAGE_BYTES);
      assertEquals(
          StatusCode.OK,
          PageCodec.validate(ByteBuffer.wrap(pages[index]), new PageHeader(), new CRC32C()));
    }
    return pages;
  }

  private static ByteBuffer payload(ByteBuffer page) {
    ByteBuffer payload = page.duplicate();
    payload.position(PageCodec.HEADER_BYTES);
    payload.limit(PageCodec.PAGE_BYTES);
    return payload.slice();
  }

  private static void copyPage(ByteBuffer page, ByteBuffer target, int offset) {
    for (int index = 0; index < PageCodec.PAGE_BYTES; index++) {
      target.put(offset + index, page.get(index));
    }
  }

  private static void refreshChecksum(ByteBuffer page) {
    FormatBytes.putInt(page, CHECKSUM_OFFSET, 0);
    FormatBytes.putInt(page, CHECKSUM_COMPLEMENT_OFFSET, 0);
    CRC32C checksum = new CRC32C();
    ByteBuffer bytes = page.duplicate();
    bytes.position(0);
    bytes.limit(PageCodec.PAGE_BYTES);
    checksum.update(bytes);
    int value = (int) checksum.getValue();
    FormatBytes.putInt(page, CHECKSUM_OFFSET, value);
    FormatBytes.putInt(page, CHECKSUM_COMPLEMENT_OFFSET, ~value);
  }

  private static void truncate(Path file, long bytes) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      channel.truncate(bytes);
      channel.force(true);
    }
  }

  private static NioDurableDirectory openDirectory(Path root) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    assertEquals(
        StatusCode.OK,
        NioDurableDirectory.openExisting(
            root, new FatalStateFence(), new NioIoCounters(), 8, result));
    return result.directory();
  }

  private static LocalWal createWal(NioDurableDirectory directory) {
    LocalWalOpenResult result = new LocalWalOpenResult();
    assertEquals(StatusCode.OK, LocalWal.create(directory, DATABASE, GENERATION, result));
    return result.wal();
  }

  private static LocalWal openExistingWal(NioDurableDirectory directory) {
    LocalWalOpenResult result = new LocalWalOpenResult();
    assertEquals(
        StatusCode.OK,
        LocalWal.openExisting(directory, DATABASE, GENERATION, result));
    return result.wal();
  }

  private static IndexedTableStore createStore(
      NioDurableDirectory directory,
      LocalWal wal) {
    IndexedTableStoreOpenResult result = new IndexedTableStoreOpenResult();
    assertEquals(
        StatusCode.OK,
        IndexedTableStore.create(
            directory, wal, DATABASE, GENERATION, databaseProviderLease(1), result));
    return result.store();
  }

  private static void createTable(IndexedTableStore store) {
    IndexedTableOpenResult result = new IndexedTableOpenResult();
    assertEquals(StatusCode.OK, IndexedTable.create(store, result));
    assertNotNull(result.table());
  }

  private enum FreeStackCorruption {
    CYCLE_OR_DUPLICATE_LINK {
      @Override
      void apply(ByteBuffer root, ByteBuffer leaf, ByteBuffer[] freePages) {
        FormatBytes.putInt(payload(freePages[5]), 0, 6);
      }
    },
    HEAD_COUNT_MISMATCH {
      @Override
      void apply(ByteBuffer root, ByteBuffer leaf, ByteBuffer[] freePages) {
        FormatBytes.putInt(payload(root), 24, 0);
      }
    },
    OUT_OF_RANGE_LINK {
      @Override
      void apply(ByteBuffer root, ByteBuffer leaf, ByteBuffer[] freePages) {
        FormatBytes.putInt(payload(freePages[6]), 0, 7);
      }
    },
    ORPHAN_FREE_PAGE {
      @Override
      void apply(ByteBuffer root, ByteBuffer leaf, ByteBuffer[] freePages) {
        FormatBytes.putInt(payload(root), 24, 2);
        FormatBytes.putInt(payload(freePages[5]), 0, 0);
      }
    },
    CHAIN_MEMBER_NON_FREE,
    FREE_PAGE_WRONG_OWNER,
    NONZERO_FREE_REMAINDER;

    void apply(ByteBuffer root, ByteBuffer leaf, ByteBuffer[] freePages) { }
  }
}
