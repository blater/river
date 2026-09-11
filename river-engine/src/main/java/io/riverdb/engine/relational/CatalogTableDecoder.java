package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Caller-owned reusable decoder for actual-count catalog table records. */
final class CatalogTableDecoder {
  private static final int INDEX_BYTES = 16;

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
    long actualMagic = longAt(scratch, source.length(), 0, 0);
    if (actualMagic != expectedMagic) {
      return knownCatalogMagic(actualMagic)
          ? StatusCode.CONFLICT : StatusCode.CORRUPTION;
    }
    return decodeCopied(
        source.length(), scratch, expectedName, schemaGate, result, expectedMagic);
  }

  StatusCode decodeForScan(
      HeapRowResult source,
      ByteBuffer scratch,
      RelationalSchemaGate schemaGate,
      TableSchema.ColumnName name,
      TableDefinition result) {
    if (source == null || scratch == null || schemaGate == null || name == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) return status;
    int bytes = source.length();
    long magic = longAt(scratch, bytes, 0, 0);
    if (magic != CatalogRecord.TABLE_MAGIC && magic != CatalogRecord.DROPPING_TABLE_MAGIC) {
      return StatusCode.CONFLICT;
    }
    int nameBytes = intAt(scratch, bytes, 16, -1);
    if (intAt(scratch, bytes, 8, -1) != CatalogRecord.TABLE_VERSION
        || nameBytes <= 0 || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || CatalogTableEncoder.HEADER_BYTES > bytes - nameBytes) {
      return StatusCode.CORRUPTION;
    }
    name.set(scratch, CatalogTableEncoder.HEADER_BYTES, nameBytes);
    if (!RelationalKey.validName(name)) return StatusCode.CORRUPTION;
    return decodeCopied(bytes, scratch, name, schemaGate, result, magic);
  }

  private StatusCode decodeCopied(
      int bytes,
      ByteBuffer source,
      CharSequence expectedName,
      RelationalSchemaGate schemaGate,
      TableDefinition result,
      long expectedMagic) {
    result.reset();
    columnDecoder.reset();
    if (bytes < CatalogTableEncoder.HEADER_BYTES
        || longAt(source, bytes, 0, 0) != expectedMagic
        || intAt(source, bytes, 8, -1) != CatalogRecord.TABLE_VERSION) {
      return StatusCode.CORRUPTION;
    }
    int tableId = intAt(source, bytes, 12, -1);
    int nameBytes = intAt(source, bytes, 16, -1);
    int columnCount = intAt(source, bytes, 20, -1);
    int indexes = intAt(source, bytes, 24, -1);
    int flags = intAt(source, bytes, 28, -1);
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
    status = columnDecoder.decode(source, bytes, offset, columnCount, tableId, flags);
    if (!status.isOk()) return status == StatusCode.RESOURCE_EXHAUSTED
        ? status : corruption(result);
    offset = columnDecoder.nextOffset();
    status = result.set(
        schemaGate, tableId, 0, TableDefinition.INDEX_NONE, -1, columnDecoder.schema());
    if (!status.isOk()) return status;
    for (int slot = 0; slot < indexes; slot++) {
      if (offset > bytes - INDEX_BYTES) return corruption(result);
      int indexTableId = source.getInt(offset);
      int state = source.getInt(offset + 4);
      int column = source.getInt(offset + 8);
      int indexFlags = source.getInt(offset + 12);
      offset += INDEX_BYTES;
      if (!validIndex(result, tableId, indexTableId, state, column, indexFlags)
          || duplicateIndex(result, indexTableId, column)) return corruption(result);
      status = result.upsertIndex(
          indexTableId, state, column, (indexFlags & 1) != 0, (indexFlags & 2) != 0);
      if (!status.isOk()) return corruption(result);
    }
    return offset == bytes && CatalogTableColumnValidator.validColumns(result)
        ? StatusCode.OK : corruption(result);
  }

  private static boolean validIndex(
      TableDefinition table,
      int tableId,
      int indexTableId,
      int state,
      int column,
      int flags) {
    return indexTableId > 0 && indexTableId <= RelationalKey.MAXIMUM_TABLE_ID
        && indexTableId != tableId && column > 0 && column < table.columnCount()
        && (state == TableDefinition.INDEX_BUILDING || state == TableDefinition.INDEX_READY
            || state == TableDefinition.INDEX_DROPPING)
        && (flags & ~3) == 0
        && ((flags & 3) != 2 || table.hasReference(column));
  }

  private static boolean duplicateIndex(TableDefinition table, int tableId, int column) {
    for (int slot = 0; slot < table.uniqueIndexCount(); slot++) {
      if (table.uniqueIndexTableId(slot) == tableId || table.uniqueIndexColumn(slot) == column) {
        return true;
      }
    }
    return false;
  }

  private static boolean nameMatches(
      ByteBuffer source, int offset, int length, CharSequence expected) {
    if (expected.length() != length) return false;
    for (int index = 0; index < length; index++) {
      if (Byte.toUnsignedInt(source.get(offset + index)) != expected.charAt(index)) return false;
    }
    return true;
  }

  private static int intAt(ByteBuffer source, int bytes, int offset, int fallback) {
    return offset <= bytes - Integer.BYTES ? source.getInt(offset) : fallback;
  }

  private static long longAt(ByteBuffer source, int bytes, int offset, long fallback) {
    return offset <= bytes - Long.BYTES ? source.getLong(offset) : fallback;
  }

  private static boolean knownCatalogMagic(long magic) {
    return CatalogSequenceCodec.matchesMagic(magic)
        || CatalogViewCodec.matchesMagic(magic)
        || magic == CatalogRecord.TABLE_MAGIC
        || magic == CatalogRecord.DROPPING_TABLE_MAGIC
        || CatalogIndexCodec.matchesMagic(magic);
  }

  private static StatusCode corruption(TableDefinition result) {
    result.reset();
    return StatusCode.CORRUPTION;
  }
}
