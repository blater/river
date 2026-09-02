package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.catalog.CatalogDefinitionRecordCodec;
import io.riverdb.base.text.Utf8Text;
import java.nio.ByteBuffer;
final class CatalogKeyPayloadWriter {
  private CatalogKeyPayloadWriter() {
  }

  static StatusCode payloadBytes(
      TableDescriptor table, int first, int count, CatalogPayloadSize result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!CatalogTableKeys.validRange(table, first, count)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int bytes = calculateBytes(table, first, count);
    if (bytes > CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    result.set(bytes);
    return StatusCode.OK;
  }

  static StatusCode encode(
      TableDescriptor table, int first, int count, ByteBuffer target, int start) {
    if (!CatalogTableKeys.validRange(table, first, count)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int bytes = calculateBytes(table, first, count);
    if (bytes > CatalogDefinitionRecordCodec.MAX_PAYLOAD_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (target == null || target.isReadOnly() || start < 0
        || start > target.limit() - bytes) return StatusCode.INVALID_EXTERNAL_INPUT;
    FormatBytes.putInt(target, start, CatalogKeyPayloadCodec.VERSION);
    FormatBytes.putInt(target, start + 4, count);
    FormatBytes.putLong(target, start + 8, CatalogIndexSchemaHash.value(table));
    int cursor = start + CatalogKeyPayloadCodec.HEADER_BYTES;
    for (int index = 0; index < count; index++) {
      KeyDescriptor key = CatalogTableKeys.at(table, first + index);
      cursor = writeKey(target, cursor, key);
    }
    return StatusCode.OK;
  }

  private static int writeKey(ByteBuffer target, int cursor, KeyDescriptor key) {
    FormatBytes.putLong(target, cursor, key.keyId());
    FormatBytes.putLong(target, cursor + 8, key.referencedKeyId());
    FormatBytes.putInt(target, cursor + 16, key.kind());
    FormatBytes.putInt(target, cursor + 20, key.isUnique() ? 1 : 0);
    FormatBytes.putInt(target, cursor + 24, key.partCount());
    int nameBytes = key.hasName()
        ? Utf8Text.encodedLength(key.name(), KeyDescriptor.MAXIMUM_NAME_LENGTH) : 0;
    FormatBytes.putInt(target, cursor + 28, nameBytes);
    cursor += CatalogKeyPayloadCodec.KEY_BYTES;
    for (int part = 0; part < key.partCount(); part++) {
      FormatBytes.putInt(target, cursor, key.columnOrdinalAt(part));
      cursor += Integer.BYTES;
    }
    if (nameBytes > 0) {
      int position = target.position();
      target.position(cursor);
      Utf8Text.encode(key.name(), KeyDescriptor.MAXIMUM_NAME_LENGTH, target);
      target.position(position);
      cursor += nameBytes;
    }
    return cursor;
  }

  private static int calculateBytes(TableDescriptor table, int first, int count) {
    int bytes = CatalogKeyPayloadCodec.HEADER_BYTES
        + count * CatalogKeyPayloadCodec.KEY_BYTES;
    for (int index = 0; index < count; index++) {
      bytes += CatalogTableKeys.at(table, first + index).partCount() * Integer.BYTES;
      KeyDescriptor key = CatalogTableKeys.at(table, first + index);
      if (key.hasName()) {
        bytes += Utf8Text.encodedLength(key.name(), KeyDescriptor.MAXIMUM_NAME_LENGTH);
      }
    }
    return bytes;
  }
}
