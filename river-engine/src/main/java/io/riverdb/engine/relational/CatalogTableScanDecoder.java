package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Caller-owned table-scan decoder with retained schema-admission storage. */
final class CatalogTableScanDecoder {
  private final CatalogTableDecoder table = new CatalogTableDecoder();

  StatusCode decode(
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
    long magic = CatalogRecord.longAt(scratch, bytes, 0, 0);
    if (magic != CatalogRecord.TABLE_MAGIC && magic != CatalogRecord.DROPPING_TABLE_MAGIC) {
      return StatusCode.CONFLICT;
    }
    int nameBytes = CatalogRecord.intAt(scratch, bytes, 16, -1);
    if (CatalogRecord.intAt(scratch, bytes, 8, -1) != CatalogRecord.TABLE_VERSION
        || nameBytes <= 0 || nameBytes > TableSchema.MAXIMUM_NAME_LENGTH
        || CatalogTableEncoder.HEADER_BYTES > bytes - nameBytes) {
      return StatusCode.CORRUPTION;
    }
    name.set(scratch, CatalogTableEncoder.HEADER_BYTES, nameBytes);
    if (!RelationalKey.validName(name)) return StatusCode.CORRUPTION;
    return table.decodeCopied(bytes, scratch, name, schemaGate, result, magic);
  }
}
