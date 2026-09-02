package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/**
 * Durable authority for monotonic positive object, schema/layout, record, and key IDs.
 * A next value of {@link Long#MAX_VALUE} is an exhausted sentinel, avoiding overflow.
 */
public final class CatalogAllocationWatermarkCodec {
  public static final int VERSION = 4;
  public static final int BYTES = 72;

  private static final long MAGIC = 0x524956434154574dL; // RIVCATWM
  static final int CHECKSUM_OFFSET = 64;
  static final int COMPLEMENT_OFFSET = 68;

  private CatalogAllocationWatermarkCodec() {
  }

  public static StatusCode encode(
      ByteBuffer target,
      int start,
      long nextObjectId,
      long nextSchemaId,
      long nextRowLayoutId,
      long nextCatalogRecordId,
      long nextKeyId,
      CRC32C checksum) {
    if (target == null || target.isReadOnly() || checksum == null || start < 0
        || start > target.limit() - BYTES
        || !validIds(
            nextObjectId, nextSchemaId, nextRowLayoutId, nextCatalogRecordId, nextKeyId)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FormatBytes.putLong(target, start, MAGIC);
    FormatBytes.putInt(target, start + 8, VERSION);
    FormatBytes.putInt(target, start + 12, BYTES);
    FormatBytes.putLong(target, start + 16, nextObjectId);
    FormatBytes.putLong(target, start + 24, nextSchemaId);
    FormatBytes.putLong(target, start + 32, nextRowLayoutId);
    FormatBytes.putLong(target, start + 40, nextCatalogRecordId);
    FormatBytes.putLong(target, start + 48, nextKeyId);
    FormatBytes.putLong(target, start + 56, 0);
    int value = FormatBytes.checksum(target, start, CHECKSUM_OFFSET, checksum);
    FormatBytes.putInt(target, start + CHECKSUM_OFFSET, value);
    FormatBytes.putInt(target, start + COMPLEMENT_OFFSET, ~value);
    return StatusCode.OK;
  }

  public static StatusCode decode(
      ByteBuffer source,
      int start,
      CatalogAllocationWatermark result,
      CRC32C checksum) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || checksum == null || start < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (start > source.limit() - BYTES) return StatusCode.CORRUPTION;
    long objectId = FormatBytes.getLong(source, start + 16);
    long schemaId = FormatBytes.getLong(source, start + 24);
    long layoutId = FormatBytes.getLong(source, start + 32);
    long recordId = FormatBytes.getLong(source, start + 40);
    long keyId = FormatBytes.getLong(source, start + 48);
    int stored = FormatBytes.getInt(source, start + CHECKSUM_OFFSET);
    if (FormatBytes.getLong(source, start) != MAGIC
        || FormatBytes.getInt(source, start + 8) != VERSION
        || FormatBytes.getInt(source, start + 12) != BYTES
        || !validIds(objectId, schemaId, layoutId, recordId, keyId)
        || FormatBytes.getLong(source, start + 56) != 0
        || FormatBytes.getInt(source, start + COMPLEMENT_OFFSET) != ~stored
        || FormatBytes.checksum(source, start, CHECKSUM_OFFSET, checksum) != stored) {
      return StatusCode.CORRUPTION;
    }
    result.set(objectId, schemaId, layoutId, recordId, keyId);
    return StatusCode.OK;
  }

  private static boolean validIds(
      long objectId, long schemaId, long layoutId, long recordId, long keyId) {
    return objectId > 0 && objectId <= CatalogKeyspace.OBJECT_ID_EXHAUSTED
        && schemaId > 0 && layoutId > 0 && recordId > 0
        && keyId > 0 && keyId <= CatalogKeyspace.KEY_ID_EXHAUSTED;
  }
}
