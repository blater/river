package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RowResult;
import java.nio.ByteBuffer;

/** Encodes bounded response metadata and values into caller-owned buffers. */
final class ProtocolResponseEncoder {
  private static final int FIXED_BYTES = 64;

  StatusCode encodeStatus(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      boolean queryActive) {
    return encode(
        target, type, requestId, status,
        queryActive ? ProtocolFrameCodec.FLAG_QUERY_ACTIVE : 0,
        0, 0, 0, 0, 0, 0, 0, null, null);
  }

  StatusCode encodeHello(
      ByteBuffer target,
      long requestId,
      StatusCode status,
      long challengeHigh,
      long challengeLow) {
    return encode(
        target, ProtocolMessageType.HELLO, requestId, status,
        0, 0, 0, 0, 0, 0, challengeHigh, challengeLow, null, null);
  }

  StatusCode encodeQueryOpen(
      ByteBuffer target,
      long requestId,
      StatusCode status,
      RiverQuery query) {
    if (status == null
        || status.isOk() && (query == null || !query.isActive())
        || !status.isOk() && query != null) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    if (!status.isOk()) {
      return encodeStatus(
          target, ProtocolMessageType.BEGIN_QUERY, requestId, status, false);
    }
    int columns = query.columnCount();
    int metadataBytes = metadataBytes(query, columns);
    if (metadataBytes < 0) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int payloadBytes = FIXED_BYTES + metadataBytes;
    long nullableMask = nullableMask(query, columns);
    StatusCode encoded = ProtocolFrameWire.begin(
        target,
        ProtocolMessageType.BEGIN_QUERY,
        requestId,
        payloadBytes,
        ProtocolFrameWire.FRAME_RESPONSE);
    if (!encoded.isOk()) {
      return encoded;
    }
    writeFixed(
        target,
        status,
        ProtocolFrameCodec.FLAG_QUERY_ACTIVE
            | ProtocolFrameCodec.FLAG_COLUMN_METADATA,
        0,
        columns,
        0,
        0,
        0,
        0,
        0,
        nullableMask);
    writeMetadata(target, query, columns);
    target.position(0);
    target.limit(ProtocolFrameCodec.HEADER_BYTES + payloadBytes);
    return StatusCode.OK;
  }

  StatusCode encodeCommand(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      CommandResult command,
      boolean queryActive) {
    int flags = queryActive ? ProtocolFrameCodec.FLAG_QUERY_ACTIVE : 0;
    if (command.transactionActive()) {
      flags |= ProtocolFrameCodec.FLAG_TRANSACTION_ACTIVE;
    }
    if (command.rowAvailable()) {
      flags |= ProtocolFrameCodec.FLAG_ROW_AVAILABLE;
    }
    return encode(
        target,
        type,
        requestId,
        status,
        flags,
        command.affectedRows(),
        command.columnCount(),
        command.commitSequence(),
        command.key(),
        0,
        0,
        0,
        command,
        null);
  }

  StatusCode encodeRow(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      RowResult row,
      long rowsReturned,
      boolean queryActive) {
    int flags = (row.isAvailable() ? ProtocolFrameCodec.FLAG_ROW_AVAILABLE : 0)
        | (queryActive ? ProtocolFrameCodec.FLAG_QUERY_ACTIVE : 0);
    return encode(
        target,
        type,
        requestId,
        status,
        flags,
        0,
        row.columnCount(),
        0,
        row.key(),
        rowsReturned,
        0,
        0,
        null,
        row);
  }

  private StatusCode encode(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      int flags,
      int rows,
      int columns,
      long commitSequence,
      long key,
      long returned,
      long challengeHigh,
      long challengeLow,
      CommandResult command,
      RowResult row) {
    if (status == null || columns < 0 || columns > CommandResult.MAXIMUM_COLUMNS) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    long nullMask = nullMask(command, row);
    if ((nullMask & ~((1L << columns) - 1)) != 0) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int valueBytes = valueBytes(command, row, columns, nullMask);
    if (valueBytes < 0) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int payloadBytes = FIXED_BYTES + columns * Integer.BYTES + valueBytes;
    StatusCode encoded = ProtocolFrameWire.begin(
        target, type, requestId, payloadBytes, ProtocolFrameWire.FRAME_RESPONSE);
    if (!encoded.isOk()) {
      return encoded;
    }
    writeFixed(
        target, status, flags, rows, columns, commitSequence, key, returned,
        challengeHigh, challengeLow, nullMask);
    int offset = writeTypes(target, command, row, columns);
    writeValues(target, offset, command, row, columns, nullMask);
    target.position(0);
    target.limit(ProtocolFrameCodec.HEADER_BYTES + payloadBytes);
    return StatusCode.OK;
  }

  private static int metadataBytes(RiverQuery query, int columns) {
    if (columns <= 0 || columns > CommandResult.MAXIMUM_COLUMNS) {
      return -1;
    }
    int bytes = 0;
    for (int index = 0; index < columns; index++) {
      CharSequence name = query.columnName(index);
      if (!validColumnName(name)
          || !SqlTypeDescriptor.isValid(query.columnTypeDescriptor(index))) {
        return -1;
      }
      bytes += Integer.BYTES + 1 + name.length();
    }
    return bytes;
  }

  private static long nullableMask(RiverQuery query, int columns) {
    long mask = 0;
    for (int index = 0; index < columns; index++) {
      if (query.columnIsNullable(index)) mask |= 1L << index;
    }
    return mask;
  }

  private static void writeMetadata(
      ByteBuffer target, RiverQuery query, int columns) {
    int offset = ProtocolFrameCodec.HEADER_BYTES + FIXED_BYTES;
    for (int index = 0; index < columns; index++) {
      target.putInt(offset, query.columnTypeDescriptor(index));
      offset += Integer.BYTES;
    }
    for (int index = 0; index < columns; index++) {
      CharSequence name = query.columnName(index);
      target.put(offset++, (byte) name.length());
      for (int character = 0; character < name.length(); character++) {
        target.put(offset++, (byte) name.charAt(character));
      }
    }
  }

  private static int valueBytes(
      CommandResult command,
      RowResult row,
      int columns,
      long nullMask) {
    int bytes = 0;
    for (int index = 0; index < columns; index++) {
      int descriptor = descriptor(command, row, index);
      if (!SqlTypeDescriptor.isValid(descriptor)) {
        return -1;
      }
      if (SqlTypeDescriptor.typeId(descriptor)
          != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        bytes += Long.BYTES;
        continue;
      }
      int characters = (nullMask & 1L << index) != 0
          ? 0 : textLength(command, row, index);
      int length = encodedTextBytes(command, row, index, characters, descriptor);
      if (length < 0) {
        return -1;
      }
      bytes += Short.BYTES + length;
    }
    return bytes;
  }

  private static int writeTypes(
      ByteBuffer target,
      CommandResult command,
      RowResult row,
      int columns) {
    int offset = ProtocolFrameCodec.HEADER_BYTES + FIXED_BYTES;
    for (int index = 0; index < columns; index++) {
      target.putInt(offset, descriptor(command, row, index));
      offset += Integer.BYTES;
    }
    return offset;
  }

  private static void writeValues(
      ByteBuffer target,
      int offset,
      CommandResult command,
      RowResult row,
      int columns,
      long nullMask) {
    for (int index = 0; index < columns; index++) {
      int descriptor = descriptor(command, row, index);
      if (SqlTypeDescriptor.typeId(descriptor)
          == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        int characters = (nullMask & 1L << index) != 0
            ? 0 : textLength(command, row, index);
        int length = encodedTextBytes(
            command, row, index, characters, descriptor);
        target.putShort(offset, (short) length);
        offset = writeText(
            target, offset + Short.BYTES, command, row, index, characters);
      } else {
        target.putLong(offset, value(command, row, index));
        offset += Long.BYTES;
      }
    }
  }

  private static int encodedTextBytes(
      CommandResult command,
      RowResult row,
      int index,
      int characters,
      int descriptor) {
    return ProtocolResponseTextEncoder.bytes(command, row, index, characters, descriptor);
  }

  private static int writeText(
      ByteBuffer target,
      int offset,
      CommandResult command,
      RowResult row,
      int index,
      int characters) {
    return ProtocolResponseTextEncoder.write(target, offset, command, row, index, characters);
  }

  private static int descriptor(
      CommandResult command, RowResult row, int index) {
    return command != null
        ? command.typeDescriptorAt(index)
        : row == null ? 0 : row.typeDescriptorAt(index);
  }

  private static int textLength(
      CommandResult command, RowResult row, int index) {
    return command != null
        ? command.textLengthAt(index)
        : row == null ? -1 : row.textLengthAt(index);
  }

  private static char textCharacter(
      CommandResult command, RowResult row, int index, int character) {
    return command != null
        ? command.textCharacterAt(index, character)
        : row.textCharacterAt(index, character);
  }

  private static long value(
      CommandResult command, RowResult row, int index) {
    return command != null
        ? command.valueAt(index) : row == null ? 0 : row.valueAt(index);
  }

  private static long nullMask(CommandResult command, RowResult row) {
    return command != null ? command.nullMask() : row == null ? 0 : row.nullMask();
  }

  private static void writeFixed(
      ByteBuffer target,
      StatusCode status,
      int flags,
      int rows,
      int columns,
      long commitSequence,
      long key,
      long returned,
      long challengeHigh,
      long challengeLow,
      long nullMask) {
    int offset = ProtocolFrameCodec.HEADER_BYTES;
    target.putInt(offset, status.stableCode());
    target.putInt(offset + 4, flags);
    target.putInt(offset + 8, rows);
    target.putInt(offset + 12, columns);
    target.putLong(offset + 16, commitSequence);
    target.putLong(offset + 24, key);
    target.putLong(offset + 32, returned);
    target.putLong(offset + 40, challengeHigh);
    target.putLong(offset + 48, challengeLow);
    target.putLong(offset + 56, nullMask);
  }

  private static boolean validColumnName(CharSequence name) {
    if (name == null || name.length() <= 0
        || name.length() > ProtocolFrameCodec.MAXIMUM_COLUMN_NAME_BYTES
        || !identifierStart(name.charAt(0))) {
      return false;
    }
    for (int index = 1; index < name.length(); index++) {
      if (!identifierPart(name.charAt(index))) {
        return false;
      }
    }
    return true;
  }

  private static boolean identifierStart(char value) {
    return value >= 'a' && value <= 'z'
        || value >= 'A' && value <= 'Z' || value == '_';
  }

  private static boolean identifierPart(char value) {
    return identifierStart(value) || value >= '0' && value <= '9';
  }
}
