package io.riverdb.engine.relational;

import java.nio.ByteBuffer;

/** Writes canonical check-program fields from either catalog schema source. */
final class CatalogTableCheckFields {
  private CatalogTableCheckFields() { }

  static void write(
      ByteBuffer target, TableSchema schema, int column, boolean present) {
    target.putInt(present ? schema.checkComparison(column) : 0);
    target.putLong(present ? schema.checkValue(column) : 0);
    target.putInt(present ? schema.checkTypeDescriptor(column) : 0);
    target.putInt(present ? schema.checkNodeCount(column) : 0);
  }

  static void write(
      ByteBuffer target, TableDefinition schema, int column, boolean present) {
    target.putInt(present ? schema.checkComparison(column) : 0);
    target.putLong(present ? schema.checkValue(column) : 0);
    target.putInt(present ? schema.checkTypeDescriptor(column) : 0);
    target.putInt(present ? schema.checkNodeCount(column) : 0);
  }
}
