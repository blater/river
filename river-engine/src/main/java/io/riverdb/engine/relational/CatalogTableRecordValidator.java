package io.riverdb.engine.relational;

import java.nio.ByteBuffer;

/** Coordinates validation of the independent table-record sections. */
final class CatalogTableRecordValidator {
  private CatalogTableRecordValidator() {
  }

  static int tableRecordBytes(
      ByteBuffer source,
      int sourceBytes,
      boolean validIndexCount,
      int nameBytes,
      int nameOffset,
      int columnsOffset,
      int columnCount,
      long defaultTextBytes,
      int checkNodeTotal) {
    return CatalogTableLayoutValidator.recordBytes(
        source,
        sourceBytes,
        validIndexCount,
        nameBytes,
        nameOffset,
        columnsOffset,
        columnCount,
        defaultTextBytes,
        checkNodeTotal);
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
    return CatalogTableLayoutValidator.valid(
        source,
        sourceBytes,
        expectedMagic,
        version,
        validIndexCount,
        nameBytes,
        nameOffset,
        columnCount,
        expectedBytes,
        notNullMask,
        defaultMask,
        defaultTextBytes,
        identityMask,
        checkMask,
        referenceMask,
        expectedNameBytes,
        checkNodeTotal,
        checkStack);
  }

  static boolean validColumns(TableDefinition table) {
    return CatalogTableColumnValidator.validColumns(table);
  }
}
