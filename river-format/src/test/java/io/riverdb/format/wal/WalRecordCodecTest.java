package io.riverdb.format.wal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class WalRecordCodecTest {
  @Test
  void roundTripsHeaderPayloadAndChecksum() {
    byte[] expected = {2, 7, 1, 8, 2, 8};
    ByteBuffer encoded = ByteBuffer.allocate(
        WalRecordCodec.encodedBytes(expected.length));
    assertEquals(
        StatusCode.OK,
        WalRecordCodec.encode(3, 11, 13, 1, 17, 2, ByteBuffer.wrap(expected), encoded));
    encoded.flip();

    WalRecordHeader header = new WalRecordHeader();
    assertEquals(StatusCode.OK, WalRecordCodec.validate(encoded, header));
    assertEquals(StatusCode.OK, WalRecordCodec.validate(encoded.asReadOnlyBuffer(), header));
    assertEquals(3, header.journalSequence());
    assertEquals(11, header.transactionId());
    assertEquals(13, header.commitSequence());
    assertEquals(17, header.formatId());
    assertEquals(2, header.formatVersion());
    ByteBuffer actual = ByteBuffer.allocate(expected.length);
    assertEquals(StatusCode.OK, WalRecordCodec.copyPayload(encoded, header, actual));
    assertArrayEquals(expected, actual.array());
  }

  @Test
  void rejectsHeaderAndPayloadCorruption() {
    ByteBuffer encoded = ByteBuffer.allocate(WalRecordCodec.encodedBytes(4));
    assertEquals(
        StatusCode.OK,
        WalRecordCodec.encode(1, 0, 0, 0, 1, 1, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), encoded));
    WalRecordHeader header = new WalRecordHeader();
    for (int index = 0; index < encoded.position(); index++) {
      byte[] corrupt = encoded.array().clone();
      corrupt[index] ^= 1;
      assertEquals(
          StatusCode.CORRUPTION,
          WalRecordCodec.validate(ByteBuffer.wrap(corrupt), header),
          "byte " + index);
    }
  }
}
