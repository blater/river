package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Fixed v2 manifest for a bounded immutable table definition or statistics set. */
public final class CatalogDefinitionManifestCodec {
  public static final int VERSION = 2;
  public static final int BYTES = 112;
  public static final int KIND_TABLE = 1;
  public static final int KIND_STATISTICS = 2;

  static final long MAGIC = 0x5249564341544d46L; // RIVCATMF
  static final int CHECKSUM_OFFSET = 104;
  static final int COMPLEMENT_OFFSET = 108;

  private CatalogDefinitionManifestCodec() {
  }

  public static StatusCode encode(
      ByteBuffer target,
      int start,
      int kind,
      long catalogRecordId,
      long objectId,
      long schemaId,
      long rowLayoutId,
      long catalogGeneration,
      long firstChildRecordId,
      int childCount,
      int columnCount,
      int keyPartCount,
      int logicalCount,
      int payloadBytes,
      int childSetChecksum,
      CRC32C checksum) {
    return CatalogDefinitionManifestWriter.encode(target, start, kind, catalogRecordId,
        objectId, schemaId, rowLayoutId, catalogGeneration, firstChildRecordId,
        childCount, columnCount, keyPartCount, logicalCount, payloadBytes,
        childSetChecksum, checksum);
  }

  public static StatusCode decode(
      ByteBuffer source, int start, CatalogDefinitionManifest result, CRC32C checksum) {
    return CatalogDefinitionManifestReader.decode(source, start, result, checksum);
  }

  /** Distinguishes manifest rows from child rows before full checksum validation while scanning. */
  public static boolean hasHeader(ByteBuffer source, int start) {
    return source != null && start >= 0 && start <= source.limit() - BYTES
        && FormatBytes.getLong(source, start) == MAGIC
        && FormatBytes.getInt(source, start + 8) == VERSION
        && FormatBytes.getInt(source, start + 12) == BYTES;
  }

  static boolean validRange(long first, int count) {
    return CatalogDefinitionManifestFormat.validRange(first, count);
  }

  static int maximumStatisticsChunks(int columns) {
    return CatalogDefinitionManifestFormat.maximumStatisticsChunks(columns);
  }
}
