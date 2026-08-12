package io.riverdb.format.wal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

final class WalRecordCodecTest {
  @Test
  void roundTripsHeaderPayloadAndChecksum() {
    byte[] expected = {2, 7, 1, 8, 2, 8};
    ByteBuffer encoded = ByteBuffer.allocate(
        WalRecordCodec.encodedBytes(expected.length));
    encoded.position(WalRecordCodec.HEADER_BYTES);
    encoded.put(expected);
    assertEquals(
        StatusCode.OK,
        WalRecordCodec.encodeReserved(
            3, 11, 13, 1, 17, 2, expected.length, encoded, new CRC32C()));

    WalRecordHeader header = new WalRecordHeader();
    assertEquals(StatusCode.OK, WalRecordCodec.validate(encoded, header, new CRC32C()));
    assertEquals(
        StatusCode.OK,
        WalRecordCodec.validate(encoded.asReadOnlyBuffer(), header, new CRC32C()));
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
    encoded.position(WalRecordCodec.HEADER_BYTES);
    encoded.put(new byte[] {1, 2, 3, 4});
    assertEquals(
        StatusCode.OK,
        WalRecordCodec.encodeReserved(1, 0, 0, 0, 1, 1, 4, encoded, new CRC32C()));
    WalRecordHeader header = new WalRecordHeader();
    for (int index = 0; index < encoded.position(); index++) {
      byte[] corrupt = encoded.array().clone();
      corrupt[index] ^= 1;
      assertEquals(
          StatusCode.CORRUPTION,
          WalRecordCodec.validate(ByteBuffer.wrap(corrupt), header, new CRC32C()),
          "byte " + index);
    }
  }
}
