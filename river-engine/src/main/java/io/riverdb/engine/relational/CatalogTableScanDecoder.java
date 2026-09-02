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
    return table.decodeForScan(source, scratch, schemaGate, name, result);
  }
}
