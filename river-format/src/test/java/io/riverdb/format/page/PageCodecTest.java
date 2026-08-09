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
