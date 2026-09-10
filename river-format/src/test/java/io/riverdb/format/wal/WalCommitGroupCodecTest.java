package io.riverdb.format.wal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

final class WalCommitGroupCodecTest {
  @Test
  void roundTripsFooterAtLargeGroupBoundary() {
    long recordBytes = 16L * 1024 * 1024 + WalRecordCodec.HEADER_BYTES;
    ByteBuffer encoded = ByteBuffer.allocate(WalCommitGroupCodec.FOOTER_BYTES);
    assertEquals(
        StatusCode.OK,
        WalCommitGroupCodec.encodeReserved(
            WalFileHeaderCodec.HEADER_BYTES,
            recordBytes,
            257,
            41,
            297,
            0x10203040,
            0x50607080,
            encoded,
            new CRC32C()));

    ByteBuffer framed = ByteBuffer.allocate(160).order(ByteOrder.BIG_ENDIAN);
    framed.position(13);
    framed.put(encoded.array());
    framed.position(13);
    framed.limit(13 + WalCommitGroupCodec.FOOTER_BYTES);
    WalCommitGroupHeader decoded = new WalCommitGroupHeader();
    assertEquals(
        StatusCode.OK,
        WalCommitGroupCodec.decodeHeader(framed, decoded, new CRC32C()));
    assertEquals(recordBytes + WalCommitGroupCodec.FOOTER_BYTES, decoded.groupBytes());
    assertEquals(recordBytes, decoded.recordBytes());
    assertEquals(257, decoded.recordCount());
    assertEquals(41, decoded.firstJournalSequence());
    assertEquals(297, decoded.lastJournalSequence());
    assertEquals(WalFileHeaderCodec.HEADER_BYTES, decoded.groupStart());
    assertEquals(0x10203040, decoded.previousDigest());
    assertEquals(0x50607080, decoded.groupChecksum());
    assertEquals(13 + WalCommitGroupCodec.FOOTER_BYTES, framed.position());
  }

  @Test
  void rejectsEveryFooterByteMutation() {
    ByteBuffer encoded = ByteBuffer.allocate(WalCommitGroupCodec.FOOTER_BYTES);
    assertEquals(
        StatusCode.OK,
        WalCommitGroupCodec.encodeReserved(
            WalFileHeaderCodec.HEADER_BYTES, 128, 2, 7, 8, 11, 13, encoded, new CRC32C()));
    WalCommitGroupHeader decoded = new WalCommitGroupHeader();
    for (int index = 0; index < encoded.capacity(); index++) {
      byte[] corrupt = encoded.array().clone();
      corrupt[index] ^= 1;
      assertEquals(
          StatusCode.CORRUPTION,
          WalCommitGroupCodec.decodeHeader(
              ByteBuffer.wrap(corrupt), decoded, new CRC32C()),
          "byte " + index);
    }
  }

  @Test
  void rejectsInconsistentCountAndOverflowingGroupBounds() {
    ByteBuffer target = ByteBuffer.allocate(WalCommitGroupCodec.FOOTER_BYTES);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        WalCommitGroupCodec.encodeReserved(
            WalFileHeaderCodec.HEADER_BYTES, 128, 2, 7, 9, 0, 0, target, new CRC32C()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        WalCommitGroupCodec.encodeReserved(
            WalFileHeaderCodec.HEADER_BYTES, Long.MAX_VALUE, 1, 7, 7, 0, 0,
            target, new CRC32C()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        WalCommitGroupCodec.encodeReserved(
            WalFileHeaderCodec.HEADER_BYTES, 128, 3, 7, 9, 0, 0, target, new CRC32C()));
  }
}
