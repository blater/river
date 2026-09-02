package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Reusable table-definition decoders owned by one relational catalog reader. */
final class RelationalCatalogTableDecoders {
  private final CatalogTableDecoder named = new CatalogTableDecoder();
  private final CatalogTableScanDecoder scanned = new CatalogTableScanDecoder();

  StatusCode named(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalSchemaGate gate,
      TableDefinition result) {
    return CatalogRecord.decodeTable(named, source, scratch, expectedName, gate, result);
  }

  StatusCode scanned(
      HeapRowResult source,
      ByteBuffer scratch,
      RelationalSchemaGate gate,
      TableSchema.ColumnName name,
      TableDefinition result) {
    return CatalogRecord.decodeTableForScan(scanned, source, scratch, gate, name, result);
  }
}
