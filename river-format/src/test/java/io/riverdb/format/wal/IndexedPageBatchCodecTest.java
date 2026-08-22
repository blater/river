package io.riverdb.format.wal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

final class IndexedPageBatchCodecTest {
  @Test
  void keepsLongIdentityWatermarksAndIntPageReferencesDistinct() {
    int bytes = IndexedPageBatchCodec.operationBytes(0, 1);
    ByteBuffer payload = ByteBuffer.allocate(bytes);
    assertEquals(
        StatusCode.OK,
        IndexedPageBatchCodec.encodeHeader(
            payload, 0, 0, 1, Long.MAX_VALUE - 1, Long.MAX_VALUE,
            Integer.MAX_VALUE, Long.MAX_VALUE));
    assertEquals(
        StatusCode.OK,
        IndexedPageBatchCodec.encodeRoot(
            payload,
            IndexedPageBatchCodec.rootOffset(0, 0, 1),
            IndexedPageBatchCodec.ROOT_LOGICAL_DIRECTORY,
            0,
            Integer.MAX_VALUE - 1,
            Long.MAX_VALUE));
    assertArrayEquals(
        HexFormat.of().parseHex(
            "355058444956495205000000010000000000000001000000feffffffffffff7f"
                + "ffffffffffffff7fffffff7f00000000ffffffffffffff7f0000000000000000"
                + "0400000000000000feffff7f00000000ffffffffffffff7f"),
        payload.array());
    IndexedPageBatchHeader header = new IndexedPageBatchHeader();
    assertEquals(StatusCode.OK, IndexedPageBatchCodec.validateStructure(payload, 0, header));
    assertEquals(Long.MAX_VALUE - 1, header.maximumLogicalRowId());
    assertEquals(Long.MAX_VALUE, header.maximumVersionId());
    assertEquals(Integer.MAX_VALUE, header.nextPageId());
    IndexedRootUpdate root = new IndexedRootUpdate();
    assertEquals(StatusCode.OK, IndexedPageBatchCodec.decodeRoot(payload, 0, header, 0, root));
    assertEquals(Integer.MAX_VALUE - 1, root.pageId());
    assertEquals(Long.MAX_VALUE, root.pageGeneration());
  }

  @Test
  void rejectsOldVersionsDuplicateRootsAndInvalidDomains() {
    int bytes = IndexedPageBatchCodec.operationBytes(0, 2);
    ByteBuffer payload = ByteBuffer.allocate(bytes);
    assertEquals(
        StatusCode.OK,
        IndexedPageBatchCodec.encodeHeader(payload, 0, 0, 2, 1, 2, 4, 4));
    int first = IndexedPageBatchCodec.rootOffset(0, 0, 2);
    int second = IndexedPageBatchCodec.rootOffset(1, 0, 2);
    assertEquals(
        StatusCode.OK,
        IndexedPageBatchCodec.encodeRoot(
            payload, first, IndexedPageBatchCodec.ROOT_PRIMARY, 1, 2, 1));
    assertEquals(
        StatusCode.OK,
        IndexedPageBatchCodec.encodeRoot(
            payload, second, IndexedPageBatchCodec.ROOT_PRIMARY, 1, 3, 1));
    assertEquals(
        StatusCode.CORRUPTION,
        IndexedPageBatchCodec.validateStructure(payload, 0, new IndexedPageBatchHeader()));

    assertEquals(
        StatusCode.OK,
        IndexedPageBatchCodec.encodeRoot(
            payload, second, IndexedPageBatchCodec.ROOT_SECONDARY, 1, 3, 1));
    FormatBytes.putInt(payload, 8, 4);
    assertEquals(
        StatusCode.CORRUPTION,
        IndexedPageBatchCodec.validateStructure(payload, 0, new IndexedPageBatchHeader()));
    FormatBytes.putInt(payload, 8, IndexedPageBatchCodec.FORMAT_VERSION);
    assertEquals(StatusCode.OK, IndexedPageBatchCodec.validateStructure(
        payload, 0, new IndexedPageBatchHeader()));

    FormatBytes.putInt(payload, second + 8, 4);
    assertEquals(
        StatusCode.CORRUPTION,
        IndexedPageBatchCodec.validateStructure(payload, 0, new IndexedPageBatchHeader()));

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        IndexedPageBatchCodec.encodeRoot(
            payload, first, IndexedPageBatchCodec.ROOT_LOGICAL_DIRECTORY, 1, 2, 1));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        IndexedPageBatchCodec.encodeRoot(
            payload, first, IndexedPageBatchCodec.ROOT_PRIMARY, 0, 2, 1));
  }

  @Test
  void maximumBatchFitsTheBoundedWalPayload() {
    int bytes = IndexedPageBatchCodec.operationBytes(
        IndexedPageBatchCodec.MAXIMUM_PAGE_COUNT,
        IndexedPageBatchCodec.MAXIMUM_ROOT_COUNT);
    assertTrue(bytes > 0);
    assertTrue(bytes <= WalRecordCodec.MAX_PAYLOAD_BYTES);
    assertEquals(0, IndexedPageBatchCodec.operationBytes(
        IndexedPageBatchCodec.MAXIMUM_PAGE_COUNT + 1, 0));
    assertEquals(0, IndexedPageBatchCodec.operationBytes(
        0, IndexedPageBatchCodec.MAXIMUM_ROOT_COUNT + 1));
  }

  @Test
  void validatesEveryPageImageBeforePublishingRootsOrAllocatorAuthority() {
    DatabaseIncarnation database = DatabaseIncarnation.of(7, 9);
    WalGeneration wal = WalGeneration.of(11);
    long recordStart = 20;
    long recordEnd = 21;
    int bytes = IndexedPageBatchCodec.operationBytes(1, 1);
    ByteBuffer payload = ByteBuffer.allocate(bytes);
    assertEquals(
        StatusCode.OK,
        IndexedPageBatchCodec.encodeHeader(payload, 0, 1, 1, 3, 4, 8, 5));
    int pageOffset = IndexedPageBatchCodec.pageOffset(0, 1);
    assertEquals(
        StatusCode.OK,
        PageCodec.encodeAt(
            database,
            wal,
            7,
            13,
            recordStart,
            recordEnd,
            0,
            payload,
            pageOffset,
            new CRC32C()));
    payload.limit(bytes);
    int rootOffset = IndexedPageBatchCodec.rootOffset(0, 1, 1);
    assertEquals(
        StatusCode.OK,
        IndexedPageBatchCodec.encodeRoot(
            payload, rootOffset, IndexedPageBatchCodec.ROOT_CATALOG, 0, 7, 13));
    IndexedPageBatchHeader header = new IndexedPageBatchHeader();
    IndexedPageBatchValidator validator = new IndexedPageBatchValidator();
    assertEquals(
        StatusCode.OK,
        validator.validate(payload, 0, database, wal, recordStart, recordEnd, header));

    assertEquals(
        StatusCode.OK,
        IndexedPageBatchCodec.encodeRoot(
            payload, rootOffset, IndexedPageBatchCodec.ROOT_CATALOG, 0, 7, 14));
    assertEquals(
        StatusCode.CORRUPTION,
        validator.validate(payload, 0, database, wal, recordStart, recordEnd, header));
    assertEquals(0, header.pageCount());

    assertEquals(
        StatusCode.CORRUPTION,
        validator.validate(
            payload, 0, DatabaseIncarnation.of(8, 9), wal, recordStart, recordEnd, header));
  }

  @Test
  void rejectsDuplicateAndUnallocatedPageImagesBeforePublication() {
    DatabaseIncarnation database = DatabaseIncarnation.of(7, 9);
    WalGeneration wal = WalGeneration.of(11);
    int bytes = IndexedPageBatchCodec.operationBytes(2, 0);
    ByteBuffer payload = ByteBuffer.allocate(bytes);
    assertEquals(
        StatusCode.OK,
        IndexedPageBatchCodec.encodeHeader(payload, 0, 2, 0, 3, 4, 8, 5));
    assertEquals(
        StatusCode.OK,
        PageCodec.encodeAt(
            database, wal, 7, 1, 20, 21, 0, payload,
            IndexedPageBatchCodec.pageOffset(0, 2), new CRC32C()));
    payload.limit(bytes);
    assertEquals(
        StatusCode.OK,
        PageCodec.encodeAt(
            database, wal, 7, 2, 20, 21, 0, payload,
            IndexedPageBatchCodec.pageOffset(1, 2), new CRC32C()));
    payload.limit(bytes);
    IndexedPageBatchHeader header = new IndexedPageBatchHeader();
    IndexedPageBatchValidator validator = new IndexedPageBatchValidator();
    assertEquals(
        StatusCode.CORRUPTION,
        validator.validate(payload, 0, database, wal, 20, 21, header));

    assertEquals(
        StatusCode.OK,
        PageCodec.encodeAt(
            database, wal, 8, 2, 20, 21, 0, payload,
            IndexedPageBatchCodec.pageOffset(1, 2), new CRC32C()));
    payload.limit(bytes);
    assertEquals(
        StatusCode.CORRUPTION,
        validator.validate(payload, 0, database, wal, 20, 21, header));
    assertEquals(0, header.pageCount());

    assertEquals(
        StatusCode.CORRUPTION,
        validator.validate(
            ByteBuffer.allocate(IndexedPageBatchCodec.HEADER_BYTES - 1),
            0,
            database,
            wal,
            20,
            21,
            header));
    assertEquals(0, header.pageCount());
  }
}
