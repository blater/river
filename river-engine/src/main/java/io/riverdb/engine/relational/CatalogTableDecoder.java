package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Decodes a table record after the catalog row has been copied into scratch. */
final class CatalogTableDecoder {
  private CatalogTableDecoder() {
  }

  static StatusCode decode(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalSchemaGate schemaGate,
      TableDefinition result,
      long expectedMagic) {
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) {
      return status;
    }
    long actualMagic = longAt(scratch, source.length(), 0, 0);
    if (actualMagic != expectedMagic) {
      return knownCatalogMagic(actualMagic)
          ? StatusCode.CONFLICT : StatusCode.CORRUPTION;
    }
    int nameBytes = intAt(scratch, source.length(), 16, -1);
    int columnCount = intAt(scratch, source.length(), 20, -1);
    int indexCount = intAt(scratch, source.length(), 24, -1);
    long notNullMask = longAt(scratch, source.length(), 28, -1);
    long defaultMask = longAt(scratch, source.length(), 36, -1);
    long defaultTextBytes = longAt(scratch, source.length(), 44, -1);
    long identityMask = longAt(scratch, source.length(), 52, -1);
    long checkMask = longAt(scratch, source.length(), CatalogRecord.TABLE_CHECK_MASK_OFFSET, -1);
    long referenceMask = longAt(
        scratch, source.length(), CatalogRecord.TABLE_REFERENCE_MASK_OFFSET, -1);
    int checkNodeTotal = intAt(
        scratch, source.length(), CatalogRecord.TABLE_CHECK_NODE_TOTAL_OFFSET, -1);
    boolean validIndexCount = indexCount >= 0
        && indexCount <= TableDefinition.MAXIMUM_INDEXES;
    int nameOffset = validIndexCount
        ? CatalogRecord.TABLE_INDEXES_OFFSET + indexCount * 16 : -1;
    int columnsOffset = nameOffset < 0 ? -1 : nameOffset + nameBytes;
    int expectedBytes = CatalogTableRecordValidator.tableRecordBytes(
        scratch,
        source.length(),
        validIndexCount,
        nameBytes,
        nameOffset,
        columnsOffset,
        columnCount,
        defaultTextBytes,
        checkNodeTotal);
    boolean valid = CatalogTableRecordValidator.valid(
        scratch,
        source.length(),
        expectedMagic,
        intAt(scratch, source.length(), 8, -1),
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
        expectedName.length(),
        checkNodeTotal,
        result.checkValidationStack());
    if (!valid) {
      return StatusCode.CORRUPTION;
    }
    if (!tableNameMatches(scratch, nameOffset, nameBytes, expectedName)) {
      return StatusCode.CONFLICT;
    }
    result.set(
        schemaGate,
        scratch.getInt(12),
        0,
        TableDefinition.INDEX_NONE,
        -1,
        scratch,
        columnsOffset,
        columnCount,
        notNullMask,
        defaultMask,
        CatalogRecord.TABLE_TYPE_DESCRIPTORS_OFFSET,
        identityMask == 1,
        checkMask,
        CatalogRecord.TABLE_CHECKS_OFFSET,
        CatalogRecord.TABLE_CHECK_VALUES_OFFSET,
        CatalogRecord.TABLE_CHECK_TYPE_DESCRIPTORS_OFFSET,
        CatalogRecord.TABLE_CHECK_NODE_COUNTS_OFFSET,
        expectedBytes - checkNodeTotal * 13,
        referenceMask,
        CatalogRecord.TABLE_REFERENCE_IDS_OFFSET,
        CatalogRecord.TABLE_DEFAULTS_OFFSET,
        CatalogRecord.TABLE_DEFAULT_KINDS_OFFSET,
        expectedBytes - checkNodeTotal * 13 - (int) defaultTextBytes,
        (int) defaultTextBytes);
    status = CatalogTableIndexDecoder.decode(
        scratch, indexCount, columnCount, referenceMask, result);
    if (!status.isOk() || !CatalogTableRecordValidator.validColumns(result)) {
      result.reset();
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    return StatusCode.OK;
  }

  private static int intAt(ByteBuffer source, int sourceBytes, int offset, int fallback) {
    return offset <= sourceBytes - Integer.BYTES ? source.getInt(offset) : fallback;
  }

  private static long longAt(ByteBuffer source, int sourceBytes, int offset, long fallback) {
    return offset <= sourceBytes - Long.BYTES ? source.getLong(offset) : fallback;
  }

  private static boolean tableNameMatches(
      ByteBuffer source, int offset, int bytes, CharSequence expected) {
    if (offset < 0 || bytes != expected.length()) {
      return false;
    }
    for (int index = 0; index < bytes; index++) {
      if (Byte.toUnsignedInt(source.get(offset + index)) != expected.charAt(index)) {
        return false;
      }
    }
    return true;
  }

  private static boolean knownCatalogMagic(long magic) {
    return CatalogSequenceCodec.matchesMagic(magic)
        || CatalogViewCodec.matchesMagic(magic)
        || magic == CatalogRecord.TABLE_MAGIC
        || magic == CatalogRecord.DROPPING_TABLE_MAGIC
        || CatalogIndexCodec.matchesMagic(magic);
  }
}
