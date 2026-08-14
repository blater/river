package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Identifies and decodes table-family rows encountered during catalog scans. */
final class CatalogTableScanDecoder {
  StatusCode decode(
      HeapRowResult source,
      ByteBuffer scratch,
      RelationalSchemaGate schemaGate,
      TableSchema.ColumnName name,
      TableDefinition result) {
    if (source == null
        || scratch == null
        || schemaGate == null
        || name == null
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    scratch.clear();
    StatusCode status = source.copyTo(scratch);
    if (!status.isOk()) {
      return status;
    }
    long magic = source.length() >= Long.BYTES ? scratch.getLong(0) : 0;
    if (magic != CatalogRecord.TABLE_MAGIC
        && magic != CatalogRecord.DROPPING_TABLE_MAGIC) {
      return StatusCode.CONFLICT;
    }
    if (source.length() < 28 || scratch.getInt(8) != CatalogRecord.TABLE_VERSION) {
      return StatusCode.CORRUPTION;
    }
    int indexCount = scratch.getInt(24);
    int nameBytes = scratch.getInt(16);
    int nameOffset = indexCount >= 0
            && indexCount <= TableDefinition.MAXIMUM_INDEXES
        ? CatalogRecord.TABLE_INDEXES_OFFSET + indexCount * 16 : -1;
    if (nameBytes <= 0
        || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || nameOffset < 0
        || nameOffset > source.length() - nameBytes) {
      return StatusCode.CORRUPTION;
    }
    name.set(scratch, nameOffset, nameBytes);
    if (!RelationalKey.validName(name)) {
      return StatusCode.CORRUPTION;
    }
    return magic == CatalogRecord.TABLE_MAGIC
        ? CatalogRecord.decodeTable(source, scratch, name, schemaGate, result)
        : CatalogRecord.decodeDroppingTable(source, scratch, name, schemaGate, result);
  }
}
