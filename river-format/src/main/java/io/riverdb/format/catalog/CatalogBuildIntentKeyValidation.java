package io.riverdb.format.catalog;

import io.riverdb.base.sql.SqlShapeLimits;

final class CatalogBuildIntentKeyValidation {
  private CatalogBuildIntentKeyValidation() { }

  static boolean valid(
      int state, long firstKeyId, int keyCount, int physicalIndexCount,
      int nextPhysicalIndex, int indexCleanupCursor, int indexCleanupHorizon) {
    int maximumKeys = SqlShapeLimits.MAX_TABLE_INDEXES + SqlShapeLimits.MAX_FOREIGN_KEYS;
    boolean range = keyCount == 0
        ? firstKeyId == 0
            || firstKeyId > 0 && firstKeyId <= CatalogKeyspace.KEY_ID_EXHAUSTED
        : keyCount <= maximumKeys && CatalogKeyspace.validKeyId(firstKeyId)
            && firstKeyId <= CatalogKeyspace.MAXIMUM_KEY_ID - keyCount + 1;
    return range && physicalIndexCount >= 0
        && physicalIndexCount <= Math.min(keyCount, SqlShapeLimits.MAX_TABLE_INDEXES)
        && nextPhysicalIndex >= 0 && nextPhysicalIndex <= physicalIndexCount
        && indexCleanupCursor >= 0 && indexCleanupCursor <= nextPhysicalIndex
        && indexCleanupHorizon >= 0
        && (indexCleanupHorizon == 0
            || state == CatalogBuildIntentCodec.STATE_CLEANUP
                && indexCleanupCursor < nextPhysicalIndex)
        && (state != CatalogBuildIntentCodec.STATE_BUILDING
            || indexCleanupCursor == 0 && indexCleanupHorizon == 0)
        && (state != CatalogBuildIntentCodec.STATE_READY
            || nextPhysicalIndex == physicalIndexCount
                && indexCleanupCursor == 0 && indexCleanupHorizon == 0);
  }
}
