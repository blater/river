package io.riverdb.format.page;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.catalog.CatalogKeyspace;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

final class PageCodecTest {
  @Test
  void encodesAndValidatesAtAbsoluteOffset() {
    int offset = 17;
    ByteBuffer container = ByteBuffer.allocate(PageCodec.PAGE_BYTES + 34);
    container.put(offset - 1, (byte) 41);
    container.put(offset + PageCodec.PAGE_BYTES, (byte) 43);
    container.put(offset + PageCodec.HEADER_BYTES, (byte) 47);
    CRC32C checksum = new CRC32C();
    assertEquals(
        StatusCode.OK,
        PageCodec.encodeAt(
            DatabaseIncarnation.of(2, 3),
            WalGeneration.of(1),
            7,
            1,
            64,
            128,
            PageCodec.PAYLOAD_KIND_SCALAR_BTREE,
            PageCodec.SCALAR_OWNER_KEY_ID,
            1,
            container,
            offset,
            checksum));
    container.limit(container.capacity());
    PageHeader header = new PageHeader();
    assertEquals(StatusCode.OK, PageCodec.validateAt(container, offset, header, checksum));
    assertEquals(2, header.databaseHigh());
    assertEquals(3, header.databaseLow());
    assertEquals(1, header.walGeneration());
    assertEquals(7, header.pageId());
    assertEquals(1, header.pageGeneration());
    assertEquals(64, header.recordStart());
    assertEquals(128, header.recordEnd());
    assertEquals(PageCodec.PAYLOAD_KIND_SCALAR_BTREE, header.payloadKind());
    assertEquals(PageCodec.SCALAR_OWNER_KEY_ID, header.ownerKeyId());
    assertEquals(1, header.payloadBytes());
    assertEquals(41, container.get(offset - 1));
    assertEquals(43, container.get(offset + PageCodec.PAGE_BYTES));
    assertEquals(47, container.get(offset + PageCodec.HEADER_BYTES));
  }

  @Test
  void validatesEveryByteAndRejectsCorruption() {
    ByteBuffer page = ByteBuffer.allocate(PageCodec.PAGE_BYTES);
    page.position(PageCodec.HEADER_BYTES);
    page.put(new byte[] {1, 3, 3, 7});
    assertEquals(
        StatusCode.OK,
        PageCodec.encode(
            DatabaseIncarnation.of(11, 13),
            WalGeneration.of(2),
            17,
            1,
            101,
            201,
            PageCodec.PAYLOAD_KIND_TUPLE_BTREE,
            29,
            4,
            page,
            new CRC32C()));
    PageHeader header = new PageHeader();
    assertEquals(StatusCode.OK, PageCodec.validate(page, header, new CRC32C()));
    assertEquals(4, header.payloadBytes());
    assertEquals(PageCodec.PAYLOAD_KIND_TUPLE_BTREE, header.payloadKind());
    assertEquals(29, header.ownerKeyId());

    for (int index = 0; index < page.capacity(); index++) {
      byte previous = page.get(index);
      page.put(index, (byte) (previous ^ 1));
      assertEquals(
          StatusCode.CORRUPTION,
          PageCodec.validate(page, header, new CRC32C()),
          "byte " + index);
      page.put(index, previous);
    }
  }

  @Test
  void rejectsInvalidPayloadIdentityOnEncode() {
    assertEncodeIdentityStatus(StatusCode.INVALID_EXTERNAL_INPUT, 0, 0);
    assertEncodeIdentityStatus(
        StatusCode.INVALID_EXTERNAL_INPUT, PageCodec.PAYLOAD_KIND_SCALAR_BTREE, 1);
    assertEncodeIdentityStatus(
        StatusCode.INVALID_EXTERNAL_INPUT, PageCodec.PAYLOAD_KIND_TUPLE_BTREE, 0);
    assertEncodeIdentityStatus(
        StatusCode.INVALID_EXTERNAL_INPUT, PageCodec.PAYLOAD_KIND_TUPLE_BTREE, -1);
    assertEncodeIdentityStatus(
        StatusCode.INVALID_EXTERNAL_INPUT,
        PageCodec.PAYLOAD_KIND_TUPLE_BTREE,
        CatalogKeyspace.KEY_ID_EXHAUSTED);
    assertEncodeIdentityStatus(
        StatusCode.OK, PageCodec.PAYLOAD_KIND_SCALAR_BTREE, PageCodec.SCALAR_OWNER_KEY_ID);
    assertEncodeIdentityStatus(StatusCode.OK, PageCodec.PAYLOAD_KIND_TUPLE_BTREE, 1);
  }

  @Test
  void rejectsInvalidPersistedPayloadIdentityAndReservedBytes() {
    ByteBuffer page = encodedTuplePage();
    PageHeader header = new PageHeader();

    putInt(page, 84, 3);
    rewriteChecksum(page);
    assertEquals(StatusCode.CORRUPTION, PageCodec.validate(page, header, new CRC32C()));

    page = encodedTuplePage();
    putLong(page, 88, 0);
    rewriteChecksum(page);
    assertEquals(StatusCode.CORRUPTION, PageCodec.validate(page, header, new CRC32C()));

    page = encodedTuplePage();
    putInt(page, 84, PageCodec.PAYLOAD_KIND_SCALAR_BTREE);
    rewriteChecksum(page);
    assertEquals(StatusCode.CORRUPTION, PageCodec.validate(page, header, new CRC32C()));

    page = encodedTuplePage();
    page.put(96, (byte) 1);
    rewriteChecksum(page);
    assertEquals(StatusCode.CORRUPTION, PageCodec.validate(page, header, new CRC32C()));
  }

  private static void assertEncodeIdentityStatus(
      StatusCode expected,
      int payloadKind,
      long ownerKeyId) {
    ByteBuffer page = ByteBuffer.allocate(PageCodec.PAGE_BYTES);
    assertEquals(
        expected,
        PageCodec.encode(
            DatabaseIncarnation.of(1, 2),
            WalGeneration.of(1),
            1,
            1,
            0,
            0,
            payloadKind,
            ownerKeyId,
            0,
            page,
            new CRC32C()));
  }

  private static ByteBuffer encodedTuplePage() {
    ByteBuffer page = ByteBuffer.allocate(PageCodec.PAGE_BYTES);
    assertEquals(
        StatusCode.OK,
        PageCodec.encode(
            DatabaseIncarnation.of(1, 2),
            WalGeneration.of(1),
            1,
            1,
            0,
            0,
            PageCodec.PAYLOAD_KIND_TUPLE_BTREE,
            7,
            0,
            page,
            new CRC32C()));
    return page;
  }

  private static void rewriteChecksum(ByteBuffer page) {
    CRC32C checksum = new CRC32C();
    checksum.update(page.array(), 0, 120);
    checksum.update(new byte[8], 0, 8);
    checksum.update(page.array(), PageCodec.HEADER_BYTES, PageCodec.MAX_PAYLOAD_BYTES);
    int value = (int) checksum.getValue();
    putInt(page, 120, value);
    putInt(page, 124, ~value);
  }

  private static void putInt(ByteBuffer page, int offset, int value) {
    page.put(offset, (byte) value);
    page.put(offset + 1, (byte) (value >>> 8));
    page.put(offset + 2, (byte) (value >>> 16));
    page.put(offset + 3, (byte) (value >>> 24));
  }

  private static void putLong(ByteBuffer page, int offset, long value) {
    putInt(page, offset, (int) value);
    putInt(page, offset + 4, (int) (value >>> 32));
  }
}
