package io.riverdb.engine.relational;

import java.nio.ByteBuffer;

/** Encodes one actual-count catalog column from either mutable or frozen schema state. */
final class CatalogTableColumnEncoder {
  private CatalogTableColumnEncoder() { }

  static void write(ByteBuffer target, TableSchema schema, int column) {
    CatalogMutableColumnEncoder.write(target, schema, column);
  }

  static void write(ByteBuffer target, TableDefinition schema, int column) {
    CatalogFrozenColumnEncoder.write(target, schema, column);
  }
}
