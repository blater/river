package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.TableDescriptor;
import java.nio.ByteBuffer;
/** Canonical payload encoding for an ordered run of immutable key descriptors. */
public final class CatalogKeyPayloadCodec {
  public static final int VERSION = 3;
  static final int HEADER_BYTES = 16;
  static final int KEY_BYTES = 32;

  private CatalogKeyPayloadCodec() { }
  public static StatusCode payloadBytes(
      TableDescriptor table, int first, int count, CatalogPayloadSize result) {
    return CatalogKeyPayloadWriter.payloadBytes(table, first, count, result);
  }

  public static StatusCode encode(
      TableDescriptor table, int first, int count, ByteBuffer target, int start) {
    return CatalogKeyPayloadWriter.encode(table, first, count, target, start);
  }

  public static long indexSchemaHash(TableDescriptor table) {
    return CatalogIndexSchemaHash.value(table); }
  static StatusCode decodeInto(
      ByteBuffer source,
      int start,
      int bytes,
      int expectedParts,
      ColumnDescriptorSet columns,
      CatalogKeyAccumulator keys,
      CatalogIndexSchemaHashState schemaHash) {
    return CatalogKeyPayloadReader.decodeInto(
        source, start, bytes, expectedParts, columns, keys, schemaHash);
  }
}
