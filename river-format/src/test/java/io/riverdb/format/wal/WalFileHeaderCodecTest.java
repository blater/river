package io.riverdb.format.wal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class WalFileHeaderCodecTest {
  @Test
  void roundTripsIdentityAndRejectsEveryCorruptByte() {
    WalFileHeader expected = new WalFileHeader(
        DatabaseIncarnation.of(23, 31),
        WalGeneration.of(5));
    ByteBuffer encoded = ByteBuffer.allocate(WalFileHeaderCodec.HEADER_BYTES);
    assertEquals(StatusCode.OK, WalFileHeaderCodec.encode(expected, encoded));
    encoded.flip();

    WalFileHeaderDecodeResult result = new WalFileHeaderDecodeResult();
    assertEquals(StatusCode.OK, WalFileHeaderCodec.decode(encoded, result));
    assertEquals(expected, result.header());
    for (int index = 0; index < encoded.capacity(); index++) {
      byte[] corrupt = encoded.array().clone();
      corrupt[index] ^= 1;
      assertEquals(
          StatusCode.CORRUPTION,
          WalFileHeaderCodec.decode(ByteBuffer.wrap(corrupt), result),
          "byte " + index);
    }
  }
}
