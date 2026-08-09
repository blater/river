package io.riverdb.format.control;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/** Fixed-size v1 codec for the database control file. */
public final class ControlFileCodec {
  public static final int RECORD_BYTES = 64;
  public static final int MAJOR_VERSION = 1;
  public static final int MINOR_VERSION = 0;

  private static final long MAGIC = 0x524956455243544cL; // RIVERCTL
  private static final int CHECKSUM_OFFSET = 56;
  private static final int CHECKSUM_COMPLEMENT_OFFSET = 60;

  private ControlFileCodec() {
  }

  public static StatusCode encode(ControlFile controlFile, ByteBuffer destination) {
    if (destination == null || destination.remaining() < RECORD_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (controlFile == null
        || controlFile.databaseIncarnation() == null
        || !controlFile.databaseIncarnation().isValid()
        || controlFile.walGeneration() == null
        || !controlFile.walGeneration().isValid()) {
      return StatusCode.INVARIANT_BROKEN;
    }

    int start = destination.position();
    ByteBuffer record = destination.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    record.limit(start + RECORD_BYTES);
    record.putLong(start, MAGIC);
    record.putInt(start + 8, MAJOR_VERSION);
    record.putInt(start + 12, MINOR_VERSION);
    record.putInt(start + 16, RECORD_BYTES);
    record.putInt(start + 20, 0);
    record.putLong(start + 24, controlFile.databaseIncarnation().high());
    record.putLong(start + 32, controlFile.databaseIncarnation().low());
    record.putLong(start + 40, controlFile.walGeneration().value());
    record.putLong(start + 48, 0L);
    int checksum = checksum(record, start);
    record.putInt(start + CHECKSUM_OFFSET, checksum);
    record.putInt(start + CHECKSUM_COMPLEMENT_OFFSET, ~checksum);
    destination.position(start + RECORD_BYTES);
    return StatusCode.OK;
  }

  public static StatusCode decode(ByteBuffer source, ControlFileDecodeResult result) {
    if (source == null || result == null || source.remaining() < RECORD_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int start = source.position();
    ByteBuffer record = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    record.limit(start + RECORD_BYTES);

    int storedChecksum = record.getInt(start + CHECKSUM_OFFSET);
    if (record.getLong(start) != MAGIC
        || record.getInt(start + 8) != MAJOR_VERSION
        || record.getInt(start + 12) != MINOR_VERSION
        || record.getInt(start + 16) != RECORD_BYTES
        || record.getInt(start + 20) != 0
        || record.getLong(start + 48) != 0L
        || record.getInt(start + CHECKSUM_COMPLEMENT_OFFSET) != ~storedChecksum
        || checksum(record, start) != storedChecksum) {
      return StatusCode.CORRUPTION;
    }

    long databaseHigh = record.getLong(start + 24);
    long databaseLow = record.getLong(start + 32);
    long walGeneration = record.getLong(start + 40);
    if ((databaseHigh == 0L && databaseLow == 0L) || walGeneration <= 0L) {
      return StatusCode.CORRUPTION;
    }
    result.set(new ControlFile(
        DatabaseIncarnation.of(databaseHigh, databaseLow),
        WalGeneration.of(walGeneration)));
    source.position(start + RECORD_BYTES);
    return StatusCode.OK;
  }

  private static int checksum(ByteBuffer record, int start) {
    ByteBuffer bytes = record.duplicate();
    bytes.position(start);
    bytes.limit(start + CHECKSUM_OFFSET);
    CRC32C crc32c = new CRC32C();
    crc32c.update(bytes);
    return (int) crc32c.getValue();
  }
}
