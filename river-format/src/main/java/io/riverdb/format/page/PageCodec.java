package io.riverdb.format.page;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Fixed 16 KiB v1 page codec over caller-owned storage. */
public final class PageCodec {
  public static final int PAGE_BYTES = 16 * 1024;
  public static final int HEADER_BYTES = 128;
  public static final int MAX_PAYLOAD_BYTES = PAGE_BYTES - HEADER_BYTES;
  public static final int VERSION = 1;
  public static final int PAGE_TYPE_SYNTHETIC = 1;

  private static final long MAGIC = 0x5249564552504147L; // RIVERPAG
  private static final int CHECKSUM_OFFSET = 120;
  private static final int COMPLEMENT_OFFSET = 124;

  private PageCodec() {
  }

  public static StatusCode encode(
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      long pageId,
      long pageGeneration,
      long recordStart,
      long recordEnd,
      int payloadBytes,
      ByteBuffer page,
      CRC32C checksum) {
    if (database == null
        || !database.isValid()
        || walGeneration == null
        || !walGeneration.isValid()
        || pageId <= 0
        || pageGeneration <= 0
        || payloadBytes < 0
        || payloadBytes > MAX_PAYLOAD_BYTES
        || ((recordStart == 0 || recordEnd == 0)
            ? recordStart != recordEnd
            : recordEnd <= recordStart)
        || page == null
        || page.capacity() < PAGE_BYTES
        || checksum == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }

    putLong(page, 0, MAGIC);
    putInt(page, 8, VERSION);
    putInt(page, 12, PAGE_BYTES);
    putInt(page, 16, PAGE_TYPE_SYNTHETIC);
    putInt(page, 20, HEADER_BYTES);
    putLong(page, 24, database.high());
    putLong(page, 32, database.low());
    putLong(page, 40, walGeneration.value());
    putLong(page, 48, pageId);
    putLong(page, 56, pageGeneration);
    putLong(page, 64, recordStart);
    putLong(page, 72, recordEnd);
    putInt(page, 80, payloadBytes);
    putInt(page, 84, 0);
    for (int index = 88; index < CHECKSUM_OFFSET; index++) {
      page.put(index, (byte) 0);
    }
    putInt(page, CHECKSUM_OFFSET, 0);
    putInt(page, COMPLEMENT_OFFSET, 0);
    for (int index = HEADER_BYTES + payloadBytes; index < PAGE_BYTES; index++) {
      page.put(index, (byte) 0);
    }
    page.position(0);
    page.limit(PAGE_BYTES);
    int pageChecksum = checksum(page, checksum);
    putInt(page, CHECKSUM_OFFSET, pageChecksum);
    putInt(page, COMPLEMENT_OFFSET, ~pageChecksum);
    page.position(0);
    page.limit(PAGE_BYTES);
    return StatusCode.OK;
  }

  public static StatusCode validate(
      ByteBuffer page,
      PageHeader result,
      CRC32C checksum) {
    if (page == null || result == null || checksum == null || page.remaining() < PAGE_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int start = page.position();
    long databaseHigh = getLong(page, start + 24);
    long databaseLow = getLong(page, start + 32);
    long walGeneration = getLong(page, start + 40);
    long pageId = getLong(page, start + 48);
    long pageGeneration = getLong(page, start + 56);
    long recordStart = getLong(page, start + 64);
    long recordEnd = getLong(page, start + 72);
    int payloadBytes = getInt(page, start + 80);
    int storedChecksum = getInt(page, start + CHECKSUM_OFFSET);
    if (getLong(page, start) != MAGIC
        || getInt(page, start + 8) != VERSION
        || getInt(page, start + 12) != PAGE_BYTES
        || getInt(page, start + 16) != PAGE_TYPE_SYNTHETIC
        || getInt(page, start + 20) != HEADER_BYTES
        || (databaseHigh == 0 && databaseLow == 0)
        || walGeneration <= 0
        || pageId <= 0
        || pageGeneration <= 0
        || payloadBytes < 0
        || payloadBytes > MAX_PAYLOAD_BYTES
        || ((recordStart == 0 || recordEnd == 0)
            ? recordStart != recordEnd
            : recordEnd <= recordStart)
        || getInt(page, start + 84) != 0
        || !reservedBytesAreZero(page, start)
        || getInt(page, start + COMPLEMENT_OFFSET) != ~storedChecksum
        || checksum(page, checksum) != storedChecksum) {
      return StatusCode.CORRUPTION;
    }
    result.set(
        databaseHigh,
        databaseLow,
        walGeneration,
        pageId,
        pageGeneration,
        recordStart,
        recordEnd,
        payloadBytes);
    return StatusCode.OK;
  }

  private static boolean reservedBytesAreZero(ByteBuffer page, int start) {
    for (int index = 88; index < CHECKSUM_OFFSET; index++) {
      if (page.get(start + index) != 0) {
        return false;
      }
    }
    return true;
  }

  private static int checksum(ByteBuffer page, CRC32C checksum) {
    int originalPosition = page.position();
    int originalLimit = page.limit();
    int start = originalPosition;
    checksum.reset();
    page.position(start);
    page.limit(start + CHECKSUM_OFFSET);
    checksum.update(page);
    for (int index = CHECKSUM_OFFSET; index < HEADER_BYTES; index++) {
      checksum.update(0);
    }
    page.limit(start + PAGE_BYTES);
    page.position(start + HEADER_BYTES);
    checksum.update(page);
    page.limit(originalLimit);
    page.position(originalPosition);
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
