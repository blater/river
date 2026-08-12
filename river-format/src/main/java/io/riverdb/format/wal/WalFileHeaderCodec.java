package io.riverdb.format.wal;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/** Fixed-size identity header for a v1 local WAL file. */
public final class WalFileHeaderCodec {
  public static final int HEADER_BYTES = 64;
  public static final int VERSION = 1;

  private static final long MAGIC = 0x524956455257464cL; // RIVERWFL
  private static final int CHECKSUM_OFFSET = 56;
  private static final int COMPLEMENT_OFFSET = 60;

  private WalFileHeaderCodec() {
  }

  public static StatusCode encode(WalFileHeader header, ByteBuffer destination) {
    if (header == null || destination == null || destination.remaining() < HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (header.databaseIncarnation() == null
        || !header.databaseIncarnation().isValid()
        || header.walGeneration() == null
        || !header.walGeneration().isValid()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    int start = destination.position();
    ByteBuffer encoded = destination.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    encoded.limit(start + HEADER_BYTES);
    encoded.putLong(start, MAGIC);
    encoded.putInt(start + 8, VERSION);
    encoded.putInt(start + 12, HEADER_BYTES);
    encoded.putLong(start + 16, header.databaseIncarnation().high());
    encoded.putLong(start + 24, header.databaseIncarnation().low());
    encoded.putLong(start + 32, header.walGeneration().value());
    encoded.putLong(start + 40, 0L);
    encoded.putLong(start + 48, 0L);
    int checksum = checksum(encoded, start);
    encoded.putInt(start + CHECKSUM_OFFSET, checksum);
    encoded.putInt(start + COMPLEMENT_OFFSET, ~checksum);
    destination.position(start + HEADER_BYTES);
    return StatusCode.OK;
  }

  public static StatusCode decode(ByteBuffer source, WalFileHeaderDecodeResult result) {
    if (source == null || result == null || source.remaining() < HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int start = source.position();
    ByteBuffer encoded = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    int stored = encoded.getInt(start + CHECKSUM_OFFSET);
    long databaseHigh = encoded.getLong(start + 16);
    long databaseLow = encoded.getLong(start + 24);
    long generation = encoded.getLong(start + 32);
    if (encoded.getLong(start) != MAGIC
        || encoded.getInt(start + 8) != VERSION
        || encoded.getInt(start + 12) != HEADER_BYTES
        || encoded.getLong(start + 40) != 0L
        || encoded.getLong(start + 48) != 0L
        || encoded.getInt(start + COMPLEMENT_OFFSET) != ~stored
        || checksum(encoded, start) != stored
        || (databaseHigh == 0L && databaseLow == 0L)
        || generation <= 0L) {
      return StatusCode.CORRUPTION;
    }
    result.set(new WalFileHeader(
        DatabaseIncarnation.of(databaseHigh, databaseLow),
        WalGeneration.of(generation)));
    source.position(start + HEADER_BYTES);
    return StatusCode.OK;
  }

  private static int checksum(ByteBuffer encoded, int start) {
    ByteBuffer bytes = encoded.duplicate();
    bytes.position(start);
    bytes.limit(start + CHECKSUM_OFFSET);
    CRC32C crc32c = new CRC32C();
    crc32c.update(bytes);
    return (int) crc32c.getValue();
  }
}
