package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Current catalog encoding for relational schemas and index-build state. */
final class CatalogRecord {
  /** The schema-shape budget bounds one durable table definition record. */
  static final int MAXIMUM_BYTES = SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES;

  static final long TABLE_MAGIC = 0x524956455254424cL; // RIVERTBL
  static final long DROPPING_TABLE_MAGIC = 0x524956455244524fL; // RIVERDRO
  static final int TABLE_VERSION = 15;

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
      CatalogTableDecoder decoder,
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalSchemaGate schemaGate,
      TableDefinition result) {
    return decoder.decode(
        source, scratch, expectedName, schemaGate, result, TABLE_MAGIC);
  }

  static StatusCode decodeDroppingTable(
      CatalogTableDecoder decoder,
      HeapRowResult source,
      ByteBuffer scratch,
      CharSequence expectedName,
      RelationalSchemaGate schemaGate,
      TableDefinition result) {
    return decoder.decode(
        source, scratch, expectedName, schemaGate, result, DROPPING_TABLE_MAGIC);
  }

  static boolean isDroppingTable(HeapRowResult source, ByteBuffer scratch) {
    scratch.clear();
    return source.copyTo(scratch).isOk()
        && source.length() >= Long.BYTES
        && scratch.getLong(0) == DROPPING_TABLE_MAGIC;
  }

  static StatusCode decodeTableForScan(
      CatalogTableScanDecoder decoder,
      HeapRowResult source,
      ByteBuffer scratch,
      RelationalSchemaGate schemaGate,
      TableSchema.ColumnName name,
      TableDefinition result) {
    return decoder.decode(
        source, scratch, schemaGate, name, result);
  }

  static void encodeDroppingTable(
      ByteBuffer target,
      int tableId,
      CharSequence name,
      TableDefinition schema) {
    CatalogTableEncoder.encodeDropping(target, tableId, name, schema);
  }

}
