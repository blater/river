package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.format.FormatBytes;
import io.riverdb.base.text.Utf8Text;
import java.nio.ByteBuffer;
final class CatalogKeyPayloadReader {
  private CatalogKeyPayloadReader() { }

  static StatusCode decodeInto(
      ByteBuffer source, int start, int bytes, int expectedParts,
      ColumnDescriptorSet columns, CatalogKeyAccumulator keys,
      CatalogIndexSchemaHashState schemaHash) {
    if (source == null || start < 0 || bytes < CatalogKeyPayloadCodec.HEADER_BYTES
        || start > source.limit() - bytes
        || FormatBytes.getInt(source, start) != CatalogKeyPayloadCodec.VERSION) {
      return StatusCode.CORRUPTION;
    }
    int keyCount = FormatBytes.getInt(source, start + 4);
    long encodedHash = FormatBytes.getLong(source, start + 8);
    if (keyCount <= 0 || keyCount > 129
        || !schemaHash.accept(encodedHash)) return StatusCode.CORRUPTION;
    int cursor = start + CatalogKeyPayloadCodec.HEADER_BYTES;
    int end = start + bytes;
    int parts = 0;
    for (int index = 0; index < keyCount; index++) {
      if (cursor > end - CatalogKeyPayloadCodec.KEY_BYTES) return StatusCode.CORRUPTION;
      int partCount = FormatBytes.getInt(source, cursor + 24);
      int nameBytes = FormatBytes.getInt(source, cursor + 28);
      if (!validKey(source, cursor, end, partCount, nameBytes)) {
        return StatusCode.CORRUPTION;
      }
      int[] ordinals;
      try {
        ordinals = new int[partCount];
      } catch (OutOfMemoryError error) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      int ordinalStart = cursor + CatalogKeyPayloadCodec.KEY_BYTES;
      for (int part = 0; part < partCount; part++) {
        ordinals[part] = FormatBytes.getInt(source, ordinalStart + part * Integer.BYTES);
      }
      int nameStart = ordinalStart + partCount * Integer.BYTES;
      if (nameBytes > 0
          && Utf8Text.validate(source, nameStart, nameBytes,
              KeyDescriptor.MAXIMUM_NAME_LENGTH) <= 0) {
        return StatusCode.CORRUPTION;
      }
      StatusCode status = keys.decodeName(source, nameStart, nameBytes);
      if (!status.isOk()) return status;
      status = keys.add(FormatBytes.getLong(source, cursor),
          FormatBytes.getInt(source, cursor + 16),
          FormatBytes.getInt(source, cursor + 20) != 0,
          FormatBytes.getLong(source, cursor + 8), ordinals, keys.decodedName(), columns);
      if (!status.isOk()) return status;
      cursor = nameStart + nameBytes;
      parts += partCount;
    }
    return cursor == end && parts == expectedParts ? StatusCode.OK : StatusCode.CORRUPTION;
  }
  private static boolean validKey(
      ByteBuffer source, int cursor, int end, int partCount, int nameBytes) {
    int flags = FormatBytes.getInt(source, cursor + 20);
    return (flags & ~1) == 0
        && partCount > 0 && partCount <= KeyDescriptor.MAXIMUM_PARTS
        && nameBytes >= 0 && nameBytes <= KeyDescriptor.MAXIMUM_NAME_LENGTH * 4
        && cursor + CatalogKeyPayloadCodec.KEY_BYTES
            <= end - (long) partCount * Integer.BYTES - nameBytes;
  }
}
