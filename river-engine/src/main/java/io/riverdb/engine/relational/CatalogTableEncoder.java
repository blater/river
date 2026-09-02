package io.riverdb.engine.relational;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Encodes one actual-count table definition record. */
final class CatalogTableEncoder {
  static final int HEADER_BYTES = 32;
  static final int IDENTITY = 1;
  static final int NULLABLE = 1;
  static final int HAS_DEFAULT = 2;
  static final int HAS_CHECK = 4;
  static final int HAS_REFERENCE = 8;

  private CatalogTableEncoder() { }

  static void encode(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      CharSequence name) {
    encode(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexTableId == 0
            ? TableDefinition.INDEX_NONE : TableDefinition.INDEX_READY,
        name,
        "key",
        "value");
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      CharSequence name) {
    encode(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        name,
        "key",
        "value");
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      CharSequence name,
      CharSequence keyColumnName,
      CharSequence valueColumnName) {
    begin(target, CatalogRecord.TABLE_MAGIC, tableId, name, 2,
        uniqueValueIndexTableId > 0 ? 1 : 0, false);
    writeMinimalColumn(target, keyColumnName, false);
    writeMinimalColumn(target, valueColumnName, true);
    if (uniqueValueIndexTableId > 0) {
      writeIndex(target, uniqueValueIndexTableId, uniqueValueIndexState, 1, true, false);
    }
    finish(target);
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      int indexColumn,
      CharSequence name,
      TableSchema schema) {
    begin(target, CatalogRecord.TABLE_MAGIC, tableId, name, schema.columnCount(),
        uniqueValueIndexTableId > 0 ? 1 : 0, schema.hasIdentity());
    for (int column = 0; column < schema.columnCount(); column++) {
      CatalogTableColumnEncoder.write(target, schema, column);
    }
    writeCheckPrograms(target, schema);
    if (uniqueValueIndexTableId > 0) {
      writeIndex(target, uniqueValueIndexTableId, uniqueValueIndexState, indexColumn, true, false);
    }
    finish(target);
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema) {
    encode(target, tableId, uniqueValueIndexTableId, uniqueValueIndexState, indexColumn,
        name, schema, true, false);
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema,
      boolean unique) {
    encode(target, tableId, valueIndexTableId, valueIndexState, indexColumn,
        name, schema, unique, false);
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema,
      boolean unique,
      boolean constraint) {
    int existing = schema.uniqueIndexCount();
    int replacement = indexSlot(schema, valueIndexTableId, indexColumn);
    int indexes = existing + (valueIndexTableId > 0 && replacement < 0 ? 1 : 0);
    begin(target, CatalogRecord.TABLE_MAGIC, tableId, name, schema.columnCount(), indexes,
        schema.hasIdentity());
    for (int column = 0; column < schema.columnCount(); column++) {
      CatalogTableColumnEncoder.write(target, schema, column);
    }
    writeCheckPrograms(target, schema);
    for (int slot = 0; slot < indexes; slot++) {
      if (slot == replacement || slot == existing && replacement < 0) {
        writeIndex(target, valueIndexTableId, valueIndexState, indexColumn, unique, constraint);
      } else {
        writeIndex(target, schema.uniqueIndexTableId(slot), schema.uniqueIndexState(slot),
            schema.uniqueIndexColumn(slot), schema.indexIsUnique(slot),
            schema.indexIsConstraint(slot));
      }
    }
    finish(target);
  }

  static void encodeDropping(
      ByteBuffer target,
      int tableId,
      CharSequence name,
      TableDefinition schema) {
    encode(target, tableId, 0, TableDefinition.INDEX_NONE, -1, name, schema);
    target.putLong(0, CatalogRecord.DROPPING_TABLE_MAGIC);
  }

  private static void begin(
      ByteBuffer target,
      long magic,
      int tableId,
      CharSequence name,
      int columns,
      int indexes,
      boolean identity) {
    target.clear();
    target.putLong(magic);
    target.putInt(CatalogRecord.TABLE_VERSION);
    target.putInt(tableId);
    target.putInt(name.length());
    target.putInt(columns);
    target.putInt(indexes);
    target.putInt(identity ? IDENTITY : 0);
    writeName(target, name);
  }

  private static void writeMinimalColumn(
      ByteBuffer target, CharSequence name, boolean nullable) {
    target.putInt(name.length());
    writeName(target, name);
    target.putInt(SqlTypeDescriptor.BIGINT);
    target.put((byte) (nullable ? NULLABLE : 0));
    target.put((byte) 0);
    target.putLong(0);
    target.putInt(0);
    target.putInt(0);
    target.putLong(0);
    target.putInt(0);
    target.putInt(0);
    target.putInt(0);
  }

  private static void writeCheckPrograms(ByteBuffer target, TableSchema schema) {
    for (int node = 0; node < schema.checkNodeCount(); node++) {
      target.put((byte) schema.checkOperator(node));
      target.putInt(schema.checkNodeDescriptor(node));
      target.putLong(schema.checkOperand(node));
    }
  }

  private static void writeCheckPrograms(ByteBuffer target, TableDefinition schema) {
    for (int column = 0; column < schema.columnCount(); column++) {
      for (int node = 0; node < schema.checkNodeCount(column); node++) {
        target.put((byte) schema.checkOperator(column, node));
        target.putInt(schema.checkNodeDescriptor(column, node));
        target.putLong(schema.checkOperand(column, node));
      }
    }
  }

  private static void writeIndex(
      ByteBuffer target,
      int tableId,
      int state,
      int column,
      boolean unique,
      boolean constraint) {
    target.putInt(tableId);
    target.putInt(state);
    target.putInt(column);
    target.putInt((unique ? 1 : 0) | (constraint ? 2 : 0));
  }

  private static void writeName(ByteBuffer target, CharSequence name) {
    for (int index = 0; index < name.length(); index++) {
      target.put((byte) name.charAt(index));
    }
  }

  private static int indexSlot(TableDefinition schema, int tableId, int column) {
    if (tableId <= 0) return -1;
    for (int slot = 0; slot < schema.uniqueIndexCount(); slot++) {
      if (schema.uniqueIndexTableId(slot) == tableId
          || schema.uniqueIndexColumn(slot) == column) return slot;
    }
    return -1;
  }

  private static void finish(ByteBuffer target) {
    target.flip();
  }
}
