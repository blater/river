package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Durable authority for monotonic allocation in the catalog continuation namespace. */
public final class CatalogAllocationWatermarkCodec {
  public static final int BYTES = 40;
  public static final int VERSION = 1;

  private static final long MAGIC = 0x524956434154574dL; // RIVCATWM
  private static final int CHECKSUM_OFFSET = 32;
  private static final int COMPLEMENT_OFFSET = 36;

  private CatalogAllocationWatermarkCodec() {
  }

  /**
   * Encodes the next unreserved ordinal. Publication must atomically persist this record,
   * every reserved segment, and the generation header. Leaked reservations are safe.
   */
  public static StatusCode encode(
      ByteBuffer target, long nextAllocation, CRC32C checksum) {
    if (target == null
        || target.isReadOnly()
        || target.remaining() < BYTES
        || nextAllocation < 0
        || nextAllocation > CatalogContinuationKey.MAXIMUM_ALLOCATION
        || checksum == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int start = target.position();
    FormatBytes.putLong(target, start, MAGIC);
    FormatBytes.putInt(target, start + 8, VERSION);
    FormatBytes.putInt(target, start + 12, BYTES);
    FormatBytes.putLong(target, start + 16, nextAllocation);
    FormatBytes.putLong(target, start + 24, 0);
    int value = FormatBytes.checksum(target, start, CHECKSUM_OFFSET, checksum);
    FormatBytes.putInt(target, start + CHECKSUM_OFFSET, value);
    FormatBytes.putInt(target, start + COMPLEMENT_OFFSET, ~value);
    target.limit(start + BYTES);
    return StatusCode.OK;
  }

  public static StatusCode decode(
      ByteBuffer source, CatalogAllocationWatermark result, CRC32C checksum) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || checksum == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (source.remaining() != BYTES) return StatusCode.CORRUPTION;
    int start = source.position();
    long next = FormatBytes.getLong(source, start + 16);
    int stored = FormatBytes.getInt(source, start + CHECKSUM_OFFSET);
    if (FormatBytes.getLong(source, start) != MAGIC
        || FormatBytes.getInt(source, start + 8) != VERSION
        || FormatBytes.getInt(source, start + 12) != BYTES
        || next < 0
        || next > CatalogContinuationKey.MAXIMUM_ALLOCATION
        || FormatBytes.getLong(source, start + 24) != 0
        || FormatBytes.getInt(source, start + COMPLEMENT_OFFSET) != ~stored
        || FormatBytes.checksum(source, start, CHECKSUM_OFFSET, checksum) != stored) {
      return StatusCode.CORRUPTION;
    }
    result.set(next);
    return StatusCode.OK;
  }
}
