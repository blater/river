package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Caller-owned reusable decoder for actual-count catalog table records. */
final class CatalogTableDecoder {
  private final CatalogColumnDecoder columnDecoder = new CatalogColumnDecoder();

  StatusCode decode(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalSchemaGate schemaGate,
      TableDefinition result,
      long expectedMagic) {
    if (source == null || scratch == null || expectedName == null
        || schemaGate == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) return status;
    long actualMagic = CatalogRecord.longAt(scratch, source.length(), 0, 0);
    if (actualMagic != expectedMagic) {
      return CatalogRecord.knownMagic(actualMagic)
          ? StatusCode.CONFLICT : StatusCode.CORRUPTION;
    }
    return decodeCopied(
        source.length(), scratch, expectedName, schemaGate, result, expectedMagic);
  }

  StatusCode decodeCopied(
      int bytes,
      ByteBuffer source,
      CharSequence expectedName,
      RelationalSchemaGate schemaGate,
      TableDefinition result,
      long expectedMagic) {
    result.reset();
    columnDecoder.reset();
    if (bytes < CatalogTableEncoder.HEADER_BYTES
        || CatalogRecord.longAt(source, bytes, 0, 0) != expectedMagic
        || CatalogRecord.intAt(source, bytes, 8, -1) != CatalogRecord.TABLE_VERSION) {
      return StatusCode.CORRUPTION;
    }
    int tableId = CatalogRecord.intAt(source, bytes, 12, -1);
    int nameBytes = CatalogRecord.intAt(source, bytes, 16, -1);
    int columnCount = CatalogRecord.intAt(source, bytes, 20, -1);
    int indexes = CatalogRecord.intAt(source, bytes, 24, -1);
    int flags = CatalogRecord.intAt(source, bytes, 28, -1);
    if (tableId <= 0 || tableId > RelationalKey.MAXIMUM_TABLE_ID
        || nameBytes <= 0 || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || columnCount < 2 || columnCount > SqlShapeLimits.MAX_TABLE_COLUMNS
        || indexes < 0 || indexes > SqlShapeLimits.MAX_SECONDARY_INDEXES
        || (flags & ~CatalogTableEncoder.IDENTITY) != 0
        || CatalogTableEncoder.HEADER_BYTES > bytes - nameBytes) {
      return StatusCode.CORRUPTION;
    }
    if (!nameMatches(
        source, CatalogTableEncoder.HEADER_BYTES, nameBytes, expectedName)) {
      return StatusCode.CONFLICT;
    }
    int offset = CatalogTableEncoder.HEADER_BYTES + nameBytes;
    StatusCode status = columnDecoder.decode(source, bytes, offset, columnCount, tableId, flags);
    if (!status.isOk()) return status == StatusCode.RESOURCE_EXHAUSTED
        ? status : corruption(result);
    offset = columnDecoder.nextOffset();
    status = result.set(
        schemaGate, tableId, 0, TableDefinition.INDEX_NONE, -1, columnDecoder.schema());
    if (!status.isOk()) return status;
    status = CatalogTableIndexDecoder.decode(source, bytes, offset, indexes, tableId, result);
    if (!status.isOk()) return corruption(result);
    offset += indexes * CatalogTableIndexDecoder.INDEX_BYTES;
    return offset == bytes && CatalogTableColumnValidator.validColumns(result)
        ? StatusCode.OK : corruption(result);
  }

  private static boolean nameMatches(
      ByteBuffer source, int offset, int length, CharSequence expected) {
    if (expected.length() != length) return false;
    for (int index = 0; index < length; index++) {
      if (Byte.toUnsignedInt(source.get(offset + index)) != expected.charAt(index)) return false;
    }
    return true;
  }

  private static StatusCode corruption(TableDefinition result) {
    result.reset();
    return StatusCode.CORRUPTION;
  }
}
