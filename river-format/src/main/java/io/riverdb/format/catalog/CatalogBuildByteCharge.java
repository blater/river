package io.riverdb.format.catalog;

import io.riverdb.base.sql.SqlShapeLimits;

/** Shared exact durable-byte admission for private catalog definition builds. */
public final class CatalogBuildByteCharge {
  private CatalogBuildByteCharge() {
  }

  public static long value(int payloadBytes, int childCount) {
    return payloadBytes
        + (long) childCount * CatalogDefinitionRecordCodec.HEADER_BYTES
        + CatalogDefinitionManifestCodec.BYTES
        + CatalogObjectHeadCodec.BYTES
        + CatalogBuildIntentCodec.BYTES;
  }

  public static long maximum() {
    return value(
        SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES,
        SqlShapeLimits.MAX_SCHEMA_CHUNKS);
  }
}
