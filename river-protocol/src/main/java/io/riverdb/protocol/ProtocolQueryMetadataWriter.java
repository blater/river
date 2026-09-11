package io.riverdb.protocol;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RowResult;
import java.nio.ByteBuffer;

/** Validates and writes the metadata section of a query-open response. */
final class ProtocolQueryMetadataWriter {
  private ProtocolQueryMetadataWriter() { }

  static int bytes(ProtocolQueryMetadata query, int columns) {
    if (columns <= 0 || columns > CommandResult.MAXIMUM_COLUMNS) {
      return -1;
    }
    int bytes = ProtocolResponseValueEncoder.nullBitmapBytes(columns);
    for (int index = 0; index < columns; index++) {
      int nameLength = query.nameLengthAt(index);
      if (nameLength <= 0 || nameLength > ProtocolFrameCodec.MAXIMUM_COLUMN_NAME_BYTES
          || !SqlTypeDescriptor.isValid(query.typeDescriptorAt(index))) {
        return -1;
      }
      bytes += Integer.BYTES + 1 + nameLength;
    }
    return bytes;
  }

  static boolean matches(ProtocolQueryMetadata metadata, RowResult row, int columns) {
    if (!row.isAvailable()) return row.columnCount() == 0;
    if (row.columnCount() != columns) return false;
    for (int index = 0; index < columns; index++) {
      if (row.typeDescriptorAt(index) != metadata.typeDescriptorAt(index)) return false;
    }
    return true;
  }

  static int write(
      ByteBuffer target, ProtocolQueryMetadata query, int columns) {
    int offset = ProtocolFrameCodec.HEADER_BYTES + ProtocolResponseFrameWriter.FIXED_BYTES;
    offset = writeNullable(target, offset, query, columns);
    for (int index = 0; index < columns; index++) {
      target.putInt(offset, query.typeDescriptorAt(index));
      offset += Integer.BYTES;
    }
    for (int index = 0; index < columns; index++) {
      int length = query.nameLengthAt(index);
      target.put(offset++, (byte) length);
      for (int character = 0; character < length; character++) {
        target.put(offset++, (byte) query.nameCharacterAt(index, character));
      }
    }
    return offset;
  }

  private static int writeNullable(
      ByteBuffer target, int offset, ProtocolQueryMetadata query, int columns) {
    int bytes = ProtocolResponseValueEncoder.nullBitmapBytes(columns);
    for (int byteIndex = 0; byteIndex < bytes; byteIndex++) {
      int value = 0;
      int first = byteIndex << 3;
      int end = Math.min(columns, first + Byte.SIZE);
      for (int index = first; index < end; index++) {
        if (query.columnIsNullable(index)) value |= 1 << (index & 7);
      }
      target.put(offset++, (byte) value);
    }
    return offset;
  }
}
