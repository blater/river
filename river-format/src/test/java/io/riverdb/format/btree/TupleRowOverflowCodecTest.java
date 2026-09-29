package io.riverdb.format.btree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class TupleRowOverflowCodecTest {
  @Test
  void admitsTheExistingMaximumRowAndChecksItsOwner() {
    ByteBuffer value = ByteBuffer.allocate(16_216);
    value.put(0, (byte) 7);
    value.put(value.limit() - 1, (byte) 9);
    ByteBuffer page = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    TupleRowOverflowHeader header = new TupleRowOverflowHeader();
    assertEquals(StatusCode.OK, TupleRowOverflowCodec.encode(
        page, 0, 81, value, 0, value.limit()));
    assertEquals(StatusCode.OK, TupleRowOverflowCodec.validate(page, 0, 81, header));
    assertEquals(value.limit(), header.valueLength());
    assertEquals(81, header.logicalRowId());
    assertEquals((byte) 7, page.get(TupleRowOverflowCodec.HEADER_BYTES));
    assertEquals((byte) 9,
        page.get(TupleRowOverflowCodec.HEADER_BYTES + value.limit() - 1));
    assertEquals(StatusCode.CORRUPTION,
        TupleRowOverflowCodec.validate(page, 0, 82, header));
    FormatBytes.putLong(page, 24, 1);
    assertEquals(StatusCode.CORRUPTION,
        TupleRowOverflowCodec.validate(page, 0, 0, header));
  }
}
