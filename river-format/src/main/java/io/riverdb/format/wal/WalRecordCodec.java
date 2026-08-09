package io.riverdb.format.wal;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/** Versioned, checksummed local WAL record framing. */
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

  public static StatusCode encode(
      long journalSequence,
      long transactionId,
      long commitSequence,
      int decisionCode,
      int formatId,
      int formatVersion,
      ByteBuffer payload,
      ByteBuffer destination) {
    if (payload == null || destination == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int payloadBytes = payload.remaining();
    int totalBytes = encodedBytes(payloadBytes);
    if (journalSequence <= 0
        || formatId <= 0
        || formatVersion <= 0
        || decisionCode < 0
        || totalBytes < 0
        || destination.remaining() < totalBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }

    int start = destination.position();
    ByteBuffer record = destination.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    record.limit(start + totalBytes);
    record.putLong(start, MAGIC);
    record.putInt(start + 8, VERSION);
    record.putInt(start + 12, HEADER_BYTES);
    record.putInt(start + 16, totalBytes);
    record.putInt(start + 20, payloadBytes);
    record.putInt(start + 24, formatId);
    record.putInt(start + 28, formatVersion);
    record.putLong(start + 32, journalSequence);
    record.putLong(start + 40, transactionId);
    record.putLong(start + 48, commitSequence);
    record.putInt(start + 56, decisionCode);
    record.putInt(start + CHECKSUM_OFFSET, 0);

    ByteBuffer payloadView = payload.duplicate();
    record.position(start + HEADER_BYTES);
    record.put(payloadView);
    int checksum = checksum(record, start, totalBytes);
    record.putInt(start + CHECKSUM_OFFSET, checksum);
    destination.position(start + totalBytes);
    return StatusCode.OK;
  }

  public static StatusCode decodeHeader(ByteBuffer source, WalRecordHeader result) {
    if (source == null || result == null || source.remaining() < HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int start = source.position();
    ByteBuffer record = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    int totalBytes = record.getInt(start + 16);
    int payloadBytes = record.getInt(start + 20);
    int formatId = record.getInt(start + 24);
    int formatVersion = record.getInt(start + 28);
    long sequence = record.getLong(start + 32);
    int decisionCode = record.getInt(start + 56);
    if (record.getLong(start) != MAGIC
        || record.getInt(start + 8) != VERSION
        || record.getInt(start + 12) != HEADER_BYTES
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
        record.getLong(start + 40),
        record.getLong(start + 48),
        decisionCode);
    return StatusCode.OK;
  }

  public static StatusCode validate(ByteBuffer source, WalRecordHeader result) {
    StatusCode status = decodeHeader(source, result);
    if (!status.isOk()) {
      return status;
    }
    if (source.remaining() < result.totalBytes()) {
      result.reset();
      return StatusCode.CORRUPTION;
    }
    int start = source.position();
    ByteBuffer record = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    int stored = record.getInt(start + CHECKSUM_OFFSET);
    int actual = checksumWithZeroedField(record, start, result.totalBytes());
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
    ByteBuffer payload = source.duplicate();
    payload.position(source.position() + HEADER_BYTES);
    payload.limit(source.position() + header.totalBytes());
    destination.put(payload);
    return StatusCode.OK;
  }

  private static int checksum(ByteBuffer record, int start, int totalBytes) {
    ByteBuffer bytes = record.duplicate();
    bytes.position(start);
    bytes.limit(start + totalBytes);
    CRC32C crc32c = new CRC32C();
    crc32c.update(bytes);
    return (int) crc32c.getValue();
  }

  private static int checksumWithZeroedField(
      ByteBuffer record,
      int start,
      int totalBytes) {
    CRC32C crc32c = new CRC32C();
    ByteBuffer prefix = record.duplicate();
    prefix.position(start);
    prefix.limit(start + CHECKSUM_OFFSET);
    crc32c.update(prefix);
    crc32c.update(0);
    crc32c.update(0);
    crc32c.update(0);
    crc32c.update(0);
    if (totalBytes > HEADER_BYTES) {
      ByteBuffer payload = record.duplicate();
      payload.position(start + HEADER_BYTES);
      payload.limit(start + totalBytes);
      crc32c.update(payload);
    }
    return (int) crc32c.getValue();
  }
}
