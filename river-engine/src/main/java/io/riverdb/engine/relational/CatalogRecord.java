package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Current catalog encoding for bounded relational schemas and index-build state. */
final class CatalogRecord {
  private static final CatalogTableScanDecoder TABLE_SCAN_DECODER =
      new CatalogTableScanDecoder();
  static final int MAXIMUM_BYTES =
      240 + TableSchema.MAXIMUM_COLUMNS * Long.BYTES
          + TableSchema.MAXIMUM_COLUMNS
          + 44 + TableSchema.MAXIMUM_CHECK_NODES * 13
          + TableDefinition.MAXIMUM_INDEXES * 16
          + 64 + TableSchema.MAXIMUM_COLUMNS * (Integer.BYTES + 64)
          + TableSchema.MAXIMUM_ROW_BYTES;

  static final long TABLE_MAGIC = 0x524956455254424cL; // RIVERTBL
  static final long DROPPING_TABLE_MAGIC = 0x524956455244524fL; // RIVERDRO
  static final int TABLE_VERSION = 14;
  static final int TABLE_CHECK_MASK_OFFSET = 60;
  static final int TABLE_CHECKS_OFFSET = 68;
  static final int TABLE_CHECK_VALUES_OFFSET = 104;
  static final int TABLE_DEFAULTS_OFFSET = 168;
  static final int TABLE_REFERENCE_MASK_OFFSET = 232;
  static final int TABLE_REFERENCE_IDS_OFFSET = 240;
  static final int TABLE_TYPE_DESCRIPTORS_OFFSET =
      TABLE_REFERENCE_IDS_OFFSET + TableSchema.MAXIMUM_COLUMNS * Integer.BYTES;
  static final int TABLE_DEFAULT_KINDS_OFFSET =
      TABLE_TYPE_DESCRIPTORS_OFFSET + TableSchema.MAXIMUM_COLUMNS * Integer.BYTES;
  static final int TABLE_CHECK_TYPE_DESCRIPTORS_OFFSET =
      TABLE_DEFAULT_KINDS_OFFSET + TableSchema.MAXIMUM_COLUMNS;
  static final int TABLE_CHECK_NODE_COUNTS_OFFSET =
      TABLE_CHECK_TYPE_DESCRIPTORS_OFFSET
          + TableSchema.MAXIMUM_COLUMNS * Integer.BYTES;
  static final int TABLE_CHECK_NODE_TOTAL_OFFSET =
      TABLE_CHECK_NODE_COUNTS_OFFSET + TableSchema.MAXIMUM_COLUMNS;
  static final int TABLE_INDEXES_OFFSET =
      TABLE_CHECK_NODE_TOTAL_OFFSET + Integer.BYTES;

  private CatalogRecord() {
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      CharSequence name) {
    CatalogTableEncoder.encode(target, tableId, uniqueValueIndexTableId, name);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      CharSequence name) {
    CatalogTableEncoder.encode(
        target, tableId, uniqueValueIndexTableId, uniqueValueIndexState, name);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      CharSequence name,
      CharSequence keyColumnName,
      CharSequence valueColumnName) {
    CatalogTableEncoder.encode(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        name,
        keyColumnName,
        valueColumnName);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      int indexColumn,
      CharSequence name,
      TableSchema schema) {
    CatalogTableEncoder.encode(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        schema);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int uniqueValueIndexTableId,
      int uniqueValueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema) {
    CatalogTableEncoder.encode(
        target,
        tableId,
        uniqueValueIndexTableId,
        uniqueValueIndexState,
        indexColumn,
        name,
        schema,
        true);
  }

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema,
      boolean unique) {
    CatalogTableEncoder.encode(
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

  static void encodeTable(
      ByteBuffer target,
      int tableId,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      CharSequence name,
      TableDefinition schema,
      boolean unique,
      boolean constraint) {
    CatalogTableEncoder.encode(
        target,
        tableId,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        name,
        schema,
        unique,
        constraint);
  }

  static StatusCode decodeTable(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalSchemaGate schemaGate,
      TableDefinition result) {
    return decodeTable(
        source, scratch, expectedName, schemaGate, result, TABLE_MAGIC);
  }

  static StatusCode decodeDroppingTable(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalSchemaGate schemaGate,
      TableDefinition result) {
    return decodeTable(
        source, scratch, expectedName, schemaGate, result, DROPPING_TABLE_MAGIC);
  }

  static boolean isDroppingTable(HeapRowResult source, ByteBuffer scratch) {
    scratch.clear();
    return source.copyTo(scratch).isOk()
        && source.length() >= Long.BYTES
        && scratch.getLong(0) == DROPPING_TABLE_MAGIC;
  }

  static StatusCode decodeTableForScan(
      HeapRowResult source,
      ByteBuffer scratch,
      RelationalSchemaGate schemaGate,
      TableSchema.ColumnName name,
      TableDefinition result) {
    return TABLE_SCAN_DECODER.decode(
        source, scratch, schemaGate, name, result);
  }

  static void encodeDroppingTable(
      ByteBuffer target,
      int tableId,
      CharSequence name,
      TableDefinition schema) {
    CatalogTableEncoder.encodeDropping(target, tableId, name, schema);
  }

  private static StatusCode decodeTable(
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalSchemaGate schemaGate,
      TableDefinition result,
      long expectedMagic) {
    return CatalogTableDecoder.decode(
        source, scratch, expectedName, schemaGate, result, expectedMagic);
  }

}
