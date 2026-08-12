package io.riverdb.format.page;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
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
            1,
            container,
            offset,
            checksum));
    container.limit(container.capacity());
    PageHeader header = new PageHeader();
    assertEquals(StatusCode.OK, PageCodec.validateAt(container, offset, header, checksum));
    assertEquals(7, header.pageId());
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
            4,
            page,
            new CRC32C()));
    PageHeader header = new PageHeader();
    assertEquals(StatusCode.OK, PageCodec.validate(page, header, new CRC32C()));
    assertEquals(4, header.payloadBytes());

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
}
