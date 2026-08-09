package io.riverdb.format.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class ControlFileCodecTest {
  @Test
  void roundTripsAndRejectsEveryCorruptedByte() {
    ControlFile expected = new ControlFile(
        DatabaseIncarnation.of(17, 29),
        WalGeneration.of(3));
    ByteBuffer encoded = ByteBuffer.allocate(ControlFileCodec.RECORD_BYTES);
    assertEquals(StatusCode.OK, ControlFileCodec.encode(expected, encoded));

    ControlFileDecodeResult result = new ControlFileDecodeResult();
    encoded.flip();
    assertEquals(StatusCode.OK, ControlFileCodec.decode(encoded, result));
    assertEquals(expected, result.controlFile());

    byte[] valid = encoded.array();
    for (int index = 0; index < valid.length; index++) {
      byte[] corrupt = valid.clone();
      corrupt[index] ^= 0x01;
      assertEquals(
          StatusCode.CORRUPTION,
          ControlFileCodec.decode(ByteBuffer.wrap(corrupt), result),
          "byte " + index);
    }
  }

  @Test
  void rejectsUnknownVersionAndShortRecord() {
    ByteBuffer encoded = ByteBuffer.allocate(ControlFileCodec.RECORD_BYTES);
    assertEquals(
        StatusCode.OK,
        ControlFileCodec.encode(
            new ControlFile(DatabaseIncarnation.of(1, 2), WalGeneration.of(1)),
            encoded));
    encoded.putInt(8, 2);

    ControlFileDecodeResult result = new ControlFileDecodeResult();
    assertEquals(StatusCode.CORRUPTION, ControlFileCodec.decode(encoded.clear(), result));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        ControlFileCodec.decode(ByteBuffer.allocate(63), result));
  }
}
