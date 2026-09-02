package io.riverdb.format.catalog;

import io.riverdb.base.sql.SqlShapeLimits;

final class CatalogBuildIntentDefinitionValidation {
  private CatalogBuildIntentDefinitionValidation() { }

  static boolean valid(
      long objectId, long schemaId, long layoutId, long generation,
      long manifestId, long firstChild, int children,
      int payloadBytes, long catalogBytes) {
    return CatalogKeyspace.validObjectHead(objectId)
        && schemaId > 0 && layoutId > 0 && generation > 0
        && manifestId > 0 && CatalogKeyspace.validDefinitionRange(firstChild, children)
        && children <= SqlShapeLimits.MAX_SCHEMA_CHUNKS
        && payloadBytes > 0 && payloadBytes <= SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES
        && catalogBytes >= payloadBytes
        && catalogBytes <= CatalogBuildByteCharge.maximum();
  }
}
