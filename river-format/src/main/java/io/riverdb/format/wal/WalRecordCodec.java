package io.riverdb.format.wal;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Versioned, checksummed local WAL record framing over caller/provider-owned storage. */
public final class WalRecordCodec {
  public static final int HEADER_BYTES = 64;
  public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
  public static final int VERSION = 1;

  private static final long MAGIC = 0x524956455257414cL; // RIVERWAL
  private static final int CHECKSUM_OFFSET = 60;

  private WalRecordCodec() {
  }

  public static int encodedBytes(int payloadBytes) {
    if (payloadBytes < 0 || payloadBytes > MAX_PAYLOAD_BYTES) {
      return -1;
    }
    return HEADER_BYTES + payloadBytes;
  }

  /**
   * Writes framing around payload bytes already present at {@link #HEADER_BYTES}.
   * The checksum object and record storage are reused by the owning WAL.
   */
  public static StatusCode encodeReserved(
      long journalSequence,
      long transactionId,
      long commitSequence,
      int decisionCode,
      int formatId,
      int formatVersion,
      int payloadBytes,
      ByteBuffer record,
      CRC32C checksum) {
    int totalBytes = encodedBytes(payloadBytes);
    if (record == null
        || checksum == null
        || journalSequence <= 0
        || formatId <= 0
        || formatVersion <= 0
        || decisionCode < 0
        || totalBytes < 0
        || record.capacity() < totalBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }

    putLong(record, 0, MAGIC);
    putInt(record, 8, VERSION);
    putInt(record, 12, HEADER_BYTES);
    putInt(record, 16, totalBytes);
    putInt(record, 20, payloadBytes);
    putInt(record, 24, formatId);
    putInt(record, 28, formatVersion);
    putLong(record, 32, journalSequence);
    putLong(record, 40, transactionId);
    putLong(record, 48, commitSequence);
    putInt(record, 56, decisionCode);
    putInt(record, CHECKSUM_OFFSET, 0);
    putInt(record, CHECKSUM_OFFSET, checksum(record, totalBytes, checksum));
    record.position(0);
    record.limit(totalBytes);
    return StatusCode.OK;
  }

  public static StatusCode decodeHeader(ByteBuffer source, WalRecordHeader result) {
    if (source == null || result == null || source.remaining() < HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int start = source.position();
    int totalBytes = getInt(source, start + 16);
    int payloadBytes = getInt(source, start + 20);
    int formatId = getInt(source, start + 24);
    int formatVersion = getInt(source, start + 28);
    long sequence = getLong(source, start + 32);
    int decisionCode = getInt(source, start + 56);
    if (getLong(source, start) != MAGIC
        || getInt(source, start + 8) != VERSION
        || getInt(source, start + 12) != HEADER_BYTES
        || totalBytes < HEADER_BYTES
        || totalBytes != HEADER_BYTES + payloadBytes
        || payloadBytes < 0
        || payloadBytes > MAX_PAYLOAD_BYTES
        || formatId <= 0
        || formatVersion <= 0
        || sequence <= 0
        || decisionCode < 0) {
      return StatusCode.CORRUPTION;
    }
    result.set(
        totalBytes,
        payloadBytes,
        formatId,
        formatVersion,
        sequence,
        getLong(source, start + 40),
        getLong(source, start + 48),
        decisionCode);
    return StatusCode.OK;
  }

  public static StatusCode validate(
      ByteBuffer source,
      WalRecordHeader result,
      CRC32C checksum) {
    if (checksum == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = decodeHeader(source, result);
    if (!status.isOk()) {
      return status;
    }
    if (source.remaining() < result.totalBytes()) {
      result.reset();
      return StatusCode.CORRUPTION;
    }
    int stored = getInt(source, source.position() + CHECKSUM_OFFSET);
    int actual = checksum(source, result.totalBytes(), checksum);
    if (stored != actual) {
      result.reset();
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  public static StatusCode copyPayload(
      ByteBuffer source,
      WalRecordHeader header,
      ByteBuffer destination) {
    if (source == null
        || header == null
        || destination == null
        || header.totalBytes() < HEADER_BYTES
        || source.remaining() < header.totalBytes()
        || destination.remaining() < header.payloadBytes()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int sourceStart = source.position() + HEADER_BYTES;
    int destinationStart = destination.position();
    for (int index = 0; index < header.payloadBytes(); index++) {
      destination.put(destinationStart + index, source.get(sourceStart + index));
    }
    destination.position(destinationStart + header.payloadBytes());
    return StatusCode.OK;
  }

  private static int checksum(ByteBuffer record, int totalBytes, CRC32C checksum) {
    int originalPosition = record.position();
    int originalLimit = record.limit();
    checksum.reset();
    record.position(0);
    record.limit(CHECKSUM_OFFSET);
    checksum.update(record);
    checksum.update(0);
    checksum.update(0);
    checksum.update(0);
    checksum.update(0);
    if (totalBytes > HEADER_BYTES) {
      record.limit(totalBytes);
      record.position(HEADER_BYTES);
      checksum.update(record);
    }
    record.limit(originalLimit);
    record.position(originalPosition);
    return (int) checksum.getValue();
  }

  private static void putInt(ByteBuffer target, int offset, int value) {
    target.put(offset, (byte) value);
    target.put(offset + 1, (byte) (value >>> 8));
    target.put(offset + 2, (byte) (value >>> 16));
    target.put(offset + 3, (byte) (value >>> 24));
  }

  private static int getInt(ByteBuffer source, int offset) {
    return Byte.toUnsignedInt(source.get(offset))
        | Byte.toUnsignedInt(source.get(offset + 1)) << 8
        | Byte.toUnsignedInt(source.get(offset + 2)) << 16
        | Byte.toUnsignedInt(source.get(offset + 3)) << 24;
  }

  private static void putLong(ByteBuffer target, int offset, long value) {
    putInt(target, offset, (int) value);
    putInt(target, offset + 4, (int) (value >>> 32));
  }

  private static long getLong(ByteBuffer source, int offset) {
    return Integer.toUnsignedLong(getInt(source, offset))
        | Integer.toUnsignedLong(getInt(source, offset + 4)) << 32;
  }
}
