package io.riverdb.engine.relational;

import java.nio.ByteBuffer;

/** Encodes one column from a frozen legacy table definition. */
final class CatalogFrozenColumnEncoder {
  private CatalogFrozenColumnEncoder() { }

  static void write(ByteBuffer target, TableDefinition schema, int column) {
    boolean hasDefault = schema.hasDefault(column);
    boolean hasCheck = schema.hasCheck(column);
    boolean hasReference = schema.hasReference(column);
    int flags = schema.isNullable(column) ? CatalogTableEncoder.NULLABLE : 0;
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
