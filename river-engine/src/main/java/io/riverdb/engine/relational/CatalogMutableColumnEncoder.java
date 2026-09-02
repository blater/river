package io.riverdb.engine.relational;

import java.nio.ByteBuffer;

/** Encodes one column from mutable CREATE TABLE schema state. */
final class CatalogMutableColumnEncoder {
  private CatalogMutableColumnEncoder() { }

  static void write(ByteBuffer target, TableSchema schema, int column) {
    long bit = 1L << (column & 63);
    int word = column >>> 6;
    boolean hasDefault = (schema.defaultWord(word) & bit) != 0;
    boolean hasCheck = (schema.checkWord(word) & bit) != 0;
    boolean hasReference = (schema.referenceWord(word) & bit) != 0;
    int flags = (schema.notNullWord(word) & bit) == 0
        ? CatalogTableEncoder.NULLABLE : 0;
    if (hasDefault) flags |= CatalogTableEncoder.HAS_DEFAULT;
    if (hasCheck) flags |= CatalogTableEncoder.HAS_CHECK;
    if (hasReference) flags |= CatalogTableEncoder.HAS_REFERENCE;
    CatalogTableColumnFields.write(
        target, schema.columnName(column), schema.typeDescriptor(column), flags);
    boolean text = CatalogTableDefaultFields.write(
        target, schema, column, hasDefault);
    CatalogTableCheckFields.write(target, schema, column, hasCheck);
    target.putInt(hasReference ? schema.referenceTableId(column) : 0);
    CatalogTableDefaultFields.writeText(target, schema, column, text);
  }
}
