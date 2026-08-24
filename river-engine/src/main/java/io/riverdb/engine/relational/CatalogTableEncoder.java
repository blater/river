package io.riverdb.engine.relational;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Encodes the current bounded catalog table record. */
final class CatalogTableEncoder {
  private CatalogTableEncoder() {
  }

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
    int indexColumn = uniqueValueIndexTableId == 0 ? -1 : 1;
    int offset = encodeTableHeader(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        2,
        1L,
        0,
        null,
        null,
        true,
        false);
    offset = encodeColumn(target, offset, keyColumnName);
    offset = encodeColumn(target, offset, valueColumnName);
    offset = encodeCheckProgram(target, offset, null, null);
    finish(target, offset);
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      int indexColumn,
      CharSequence name,
      TableSchema schema) {
    int offset = encodeTableHeader(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        schema.columnCount(),
        schema.notNullMask(),
        schema.defaultMask(),
        schema,
        null,
        true,
        false);
    offset = encodeColumnsAndDefaults(target, offset, schema);
    offset = encodeCheckProgram(target, offset, schema, null);
    finish(target, offset);
  }

  static void encode(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema) {
    encode(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        schema,
        true,
        false);
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
    encode(
        target,
        tableId,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        name,
        schema,
        unique,
        false);
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
    int offset = encodeTableHeader(
        target,
        tableId,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        name,
        schema.columnCount(),
        schema.notNullMask(),
        schema.defaultMask(),
        null,
        schema,
        unique,
        constraint);
    for (int index = 0; index < schema.columnCount(); index++) {
      offset = encodeColumn(target, offset, schema.columnName(index));
    }
    for (int index = 0; index < schema.defaultTextBytes(); index++) {
      target.put(offset++, schema.defaultTextByte(index));
    }
    offset = encodeCheckProgram(target, offset, null, schema);
    finish(target, offset);
  }

  static void encodeDropping(
      ByteBuffer target,
      int tableId,
      CharSequence name,
      TableDefinition schema) {
    encode(
        target,
        tableId,
        0,
        TableDefinition.INDEX_NONE,
        -1,
        name,
        schema);
    target.putLong(0, CatalogRecord.DROPPING_TABLE_MAGIC);
  }

  private static int encodeTableHeader(
      ByteBuffer target,
      int tableId,
      int indexTableId,
      int indexState,
      int indexColumn,
      CharSequence name,
      int columnCount,
      long notNullMask,
      long defaultMask,
      TableSchema definition,
      TableDefinition existing,
      boolean unique,
      boolean constraint) {
    return CatalogTableHeaderEncoder.encode(target, tableId, indexTableId, indexState, indexColumn,
        name, columnCount, notNullMask, defaultMask, definition, existing, unique, constraint);
  }

  private static int encodeCheckProgram(
      ByteBuffer target,
      int offset,
      TableSchema definition,
      TableDefinition existing) {
    int nodes = definition != null
        ? definition.checkNodeCount()
        : existing != null ? existing.checkNodeCount() : 0;
    for (int node = 0; node < nodes; node++) {
      int operator = definition != null
          ? definition.checkOperator(node) : existing.checkOperatorAt(node);
      int descriptor = definition != null
          ? definition.checkNodeDescriptor(node) : existing.checkNodeDescriptorAt(node);
      long operand = definition != null
          ? definition.checkOperand(node) : existing.checkOperandAt(node);
      target.put(offset, (byte) operator);
      target.putInt(offset + 1, descriptor);
      target.putLong(offset + 5, operand);
      offset += 13;
    }
    return offset;
  }

  private static int encodeColumnsAndDefaults(
      ByteBuffer target,
      int offset,
      TableSchema schema) {
    for (int index = 0; index < schema.columnCount(); index++) {
      offset = encodeColumn(target, offset, schema.columnName(index));
    }
    for (int index = 0; index < schema.defaultTextBytes(); index++) {
      target.put(offset++, schema.defaultTextByte(index));
    }
    return offset;
  }

  private static int encodeColumn(ByteBuffer target, int offset, CharSequence name) {
    target.putInt(offset, name.length());
    offset += Integer.BYTES;
    for (int index = 0; index < name.length(); index++) {
      target.put(offset + index, (byte) name.charAt(index));
    }
    return offset + name.length();
  }

  private static void finish(ByteBuffer target, int offset) {
    target.position(0);
    target.limit(offset);
  }
}
