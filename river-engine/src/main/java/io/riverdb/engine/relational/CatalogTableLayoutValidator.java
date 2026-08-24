package io.riverdb.engine.relational;

import java.nio.ByteBuffer;

/** Validates table-record framing before section-specific validation. */
final class CatalogTableLayoutValidator {
  private CatalogTableLayoutValidator() {
  }

  static int recordBytes(
      ByteBuffer source,
      int sourceBytes,
      boolean validIndexCount,
      int nameBytes,
      int nameOffset,
      int columnsOffset,
      int columnCount,
      long defaultTextBytes,
      int checkNodeTotal) {
    if (!validIndexCount
        || nameBytes <= 0
        || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || columnsOffset < nameOffset
        || columnsOffset > sourceBytes
        || columnCount < 2
        || columnCount > TableSchema.MAXIMUM_COLUMNS
        || checkNodeTotal < 0
        || checkNodeTotal > TableSchema.MAXIMUM_CHECK_NODES) {
      return -1;
    }
    int expectedBytes = columnsOffset;
    for (int index = 0; index < columnCount; index++) {
      if (expectedBytes > sourceBytes - Integer.BYTES) {
        return -1;
      }
      int columnBytes = source.getInt(expectedBytes);
      if (columnBytes <= 0
          || columnBytes > TableSchema.MAXIMUM_NAME_LENGTH
          || expectedBytes > sourceBytes - Integer.BYTES - columnBytes) {
        return -1;
      }
      expectedBytes += Integer.BYTES + columnBytes;
    }
    if (defaultTextBytes < 0
        || defaultTextBytes > TableSchema.MAXIMUM_ROW_BYTES
        || expectedBytes > sourceBytes - defaultTextBytes - checkNodeTotal * 13L) {
      return -1;
    }
    return expectedBytes + (int) defaultTextBytes + checkNodeTotal * 13;
  }

  static boolean valid(
      ByteBuffer source,
      int sourceBytes,
      long expectedMagic,
      int version,
      boolean validIndexCount,
      int nameBytes,
      int nameOffset,
      int columnCount,
      int expectedBytes,
      long notNullMask,
      long defaultMask,
      long defaultTextBytes,
      long identityMask,
      long checkMask,
      long referenceMask,
      int expectedNameBytes,
      int checkNodeTotal,
      int[] checkStack) {
    int tableId = source.getInt(12);
    long columnMask = (1L << columnCount) - 1;
    return version == CatalogRecord.TABLE_VERSION
        && validIndexCount
        && nameOffset >= 0
        && sourceBytes >= nameOffset + 1
        && sourceBytes == expectedBytes
        && source.getLong(0) == expectedMagic
        && tableId > 0
        && tableId <= RelationalKey.MAXIMUM_TABLE_ID
        && nameBytes > 0
        && nameBytes <= TableSchema.MAXIMUM_NAME_LENGTH
        && columnCount >= 2
        && columnCount <= TableSchema.MAXIMUM_COLUMNS
        && (notNullMask & 1) != 0
        && (notNullMask & ~columnMask) == 0
        && (defaultMask & 1) == 0
        && (defaultMask & ~columnMask) == 0
        && CatalogTableColumnValidator.validTypeDescriptors(source, columnCount)
        && CatalogTableDefaultValidator.valid(
            source,
            columnCount,
            defaultMask,
            (int) defaultTextBytes,
            expectedBytes - checkNodeTotal * 13 - (int) defaultTextBytes)
        && (identityMask == 0 || identityMask == 1)
        && (checkMask & ~columnMask) == 0
        && CatalogTableCheckValidator.valid(
            source, sourceBytes, columnCount, checkMask, checkStack)
        && (referenceMask & 1) == 0
        && (referenceMask & ~columnMask) == 0
        && CatalogTableColumnValidator.validReferences(
            source, columnCount, referenceMask, tableId)
        && nameBytes == expectedNameBytes;
  }
}
