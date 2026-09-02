package io.riverdb.format.page;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Fixed 16,384-byte v3 page codec over caller-owned storage. */
public final class PageCodec {
  public static final int PAGE_BYTES = 16 * 1024;
  public static final int HEADER_BYTES = 128;
  public static final int MAX_PAYLOAD_BYTES = PAGE_BYTES - HEADER_BYTES;
  public static final int VERSION = 3;
  public static final int PAGE_TYPE_SYNTHETIC = 1;
  public static final int PAYLOAD_KIND_SCALAR_BTREE = 1;
  public static final int PAYLOAD_KIND_TUPLE_BTREE = 2;
  public static final int PAYLOAD_KIND_FREE = 3;
  public static final int FREE_PAYLOAD_BYTES = Integer.BYTES;
  public static final long SCALAR_OWNER_KEY_ID = 0;

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
      int payloadKind,
      long ownerKeyId,
      int payloadBytes,
      ByteBuffer page,
      CRC32C checksum) {
    return encodeAt(
        database,
        walGeneration,
        pageId,
        pageGeneration,
        recordStart,
        recordEnd,
        payloadKind,
        ownerKeyId,
        payloadBytes,
        page,
        0,
        checksum);
  }

  /** Encodes one page at an absolute offset in caller/provider-owned storage. */
  public static StatusCode encodeAt(
      DatabaseIncarnation database,
      WalGeneration walGeneration,
      long pageId,
      long pageGeneration,
      long recordStart,
      long recordEnd,
      int payloadKind,
      long ownerKeyId,
      int payloadBytes,
      ByteBuffer page,
      int start,
      CRC32C checksum) {
    if (database == null
        || !database.isValid()
        || walGeneration == null
        || !walGeneration.isValid()
        || pageId <= 0
        || pageGeneration <= 0
        || !PagePayloadIdentity.isValid(payloadKind, ownerKeyId, payloadBytes)
        || ((recordStart == 0 || recordEnd == 0)
            ? recordStart != recordEnd
            : recordEnd <= recordStart)
        || page == null
        || start < 0
        || page.limit() - start < PAGE_BYTES
        || checksum == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }

    putLong(page, start, MAGIC);
    putInt(page, start + 8, VERSION);
    putInt(page, start + 12, PAGE_BYTES);
    putInt(page, start + 16, PAGE_TYPE_SYNTHETIC);
    putInt(page, start + 20, HEADER_BYTES);
    putLong(page, start + 24, database.high());
    putLong(page, start + 32, database.low());
    putLong(page, start + 40, walGeneration.value());
    putLong(page, start + 48, pageId);
    putLong(page, start + 56, pageGeneration);
    putLong(page, start + 64, recordStart);
    putLong(page, start + 72, recordEnd);
    putInt(page, start + 80, payloadBytes);
    putInt(page, start + 84, payloadKind);
    putLong(page, start + 88, ownerKeyId);
    for (int index = 96; index < CHECKSUM_OFFSET; index++) {
      page.put(start + index, (byte) 0);
    }
    putInt(page, start + CHECKSUM_OFFSET, 0);
    putInt(page, start + COMPLEMENT_OFFSET, 0);
    for (int index = HEADER_BYTES + payloadBytes; index < PAGE_BYTES; index++) {
      page.put(start + index, (byte) 0);
    }
    int pageChecksum = checksum(page, start, checksum);
    putInt(page, start + CHECKSUM_OFFSET, pageChecksum);
    putInt(page, start + COMPLEMENT_OFFSET, ~pageChecksum);
    page.position(start);
    page.limit(start + PAGE_BYTES);
    return StatusCode.OK;
  }

  public static StatusCode validate(
      ByteBuffer page,
      PageHeader result,
      CRC32C checksum) {
    if (page == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return validateAt(page, page.position(), result, checksum);
  }

  /** Validates one page at an absolute offset without creating a buffer view. */
  public static StatusCode validateAt(
      ByteBuffer page,
      int start,
      PageHeader result,
      CRC32C checksum) {
    if (page == null
        || result == null
        || checksum == null
        || start < 0
        || page.limit() - start < PAGE_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    long databaseHigh = getLong(page, start + 24);
    long databaseLow = getLong(page, start + 32);
    long walGeneration = getLong(page, start + 40);
    long pageId = getLong(page, start + 48);
    long pageGeneration = getLong(page, start + 56);
    long recordStart = getLong(page, start + 64);
    long recordEnd = getLong(page, start + 72);
    int payloadBytes = getInt(page, start + 80);
    int payloadKind = getInt(page, start + 84);
    long ownerKeyId = getLong(page, start + 88);
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
        || !PagePayloadIdentity.isValid(payloadKind, ownerKeyId, payloadBytes)
        || ((recordStart == 0 || recordEnd == 0)
            ? recordStart != recordEnd
            : recordEnd <= recordStart)
        || !reservedBytesAreZero(page, start)
        || payloadKind == PAYLOAD_KIND_FREE && !freeRemainderIsZero(page, start)
        || getInt(page, start + COMPLEMENT_OFFSET) != ~storedChecksum
        || checksum(page, start, checksum) != storedChecksum) {
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
        payloadKind,
        ownerKeyId,
        payloadBytes);
    return StatusCode.OK;
  }

  private static boolean reservedBytesAreZero(ByteBuffer page, int start) {
    for (int index = 96; index < CHECKSUM_OFFSET; index++) {
      if (page.get(start + index) != 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean freeRemainderIsZero(ByteBuffer page, int start) {
    for (int index = HEADER_BYTES + FREE_PAYLOAD_BYTES; index < PAGE_BYTES; index++) {
      if (page.get(start + index) != 0) return false;
    }
    return true;
  }

  private static int checksum(ByteBuffer page, int start, CRC32C checksum) {
    int originalPosition = page.position();
    int originalLimit = page.limit();
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
