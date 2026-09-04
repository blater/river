package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.PreparedOpenResult;
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

  StatusCode encodePrepared(
      ByteBuffer target,
      long requestId,
      StatusCode status,
      PreparedOpenResult prepared) {
    if (status == null || status.isOk() && (prepared == null || prepared.handle() <= 0)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    return encode(
        target, ProtocolMessageType.PREPARE, requestId, status,
        status.isOk() && prepared.query() ? ProtocolFrameCodec.FLAG_PREPARED_QUERY : 0,
        status.isOk() ? prepared.parameterCount() : 0,
        0, 0, status.isOk() ? prepared.handle() : 0,
        0, 0, 0, null, null);
  }

  StatusCode encodeQueryOpen(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      ProtocolQueryMetadata metadata,
      RowResult row,
      long rowsReturned,
      CommandResult completion,
      boolean queryActive) {
    if ((type != ProtocolMessageType.BEGIN_QUERY
            && type != ProtocolMessageType.BEGIN_PREPARED_QUERY)
        || status == null
        || status.isOk() && (metadata == null
            || row == null
            || queryActive == (completion != null))
        || !status.isOk() && (metadata != null || row != null || completion != null)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    if (!status.isOk()) {
      return encodeStatus(
          target, type, requestId, status, queryActive);
    }
    int columns = metadata.columnCount();
    int metadataBytes = metadataBytes(metadata, columns);
    int valueBytes = row.isAvailable()
        ? ProtocolResponseValueEncoder.bytes(null, row, columns) : 0;
    if (metadataBytes < 0 || valueBytes < 0 || !matches(metadata, row, columns)
        || queryActive && !row.isAvailable()
        || rowsReturned != (row.isAvailable() ? 1 : 0)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int rowNullBytes = row.isAvailable()
        ? ProtocolResponseValueEncoder.nullBitmapBytes(columns) : 0;
    int payloadBytes = FIXED_BYTES + metadataBytes + rowNullBytes + valueBytes;
    StatusCode encoded = ProtocolFrameWire.begin(
        target,
        type,
        requestId,
        payloadBytes,
        ProtocolFrameWire.FRAME_RESPONSE);
    if (!encoded.isOk()) {
      return encoded;
    }
    int flags = ProtocolFrameCodec.FLAG_COLUMN_METADATA
        | (queryActive ? ProtocolFrameCodec.FLAG_QUERY_ACTIVE
            : ProtocolFrameCodec.FLAG_END_OF_STREAM)
        | (row.isAvailable() ? ProtocolFrameCodec.FLAG_ROW_AVAILABLE : 0);
    if (completion != null && completion.transactionActive()) {
      flags |= ProtocolFrameCodec.FLAG_TRANSACTION_ACTIVE;
    }
    writeFixed(target, status, flags,
        completion == null ? 0 : completion.affectedRows(), columns,
        completion == null ? 0 : completion.commitSequence(),
        row.isAvailable() ? row.key() : 0, rowsReturned, 0, 0,
        rowNullBytes, metadataBytes);
    int offset = writeMetadata(target, metadata, columns);
    if (row.isAvailable()) {
      offset = ProtocolResponseValueEncoder.writeNulls(
          target, offset, null, row, columns);
      if (!ProtocolResponseValueEncoder.writeValues(
          target, offset, null, row, columns)) {
        return ProtocolFrameWire.invalidTarget(target);
      }
    }
    return ProtocolResponseSegmenter.finish(
        target, type, requestId, payloadBytes);
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
      CommandResult completion,
      boolean queryActive) {
    if (type != ProtocolMessageType.FETCH || status == null || row == null
        || status.isOk() && queryActive == (completion != null)
        || !status.isOk() && queryActive && completion != null) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    if (!status.isOk() && completion == null) {
      return encodeStatus(target, type, requestId, status, queryActive);
    }
    int flags = (row.isAvailable() ? ProtocolFrameCodec.FLAG_ROW_AVAILABLE : 0)
        | (queryActive ? ProtocolFrameCodec.FLAG_QUERY_ACTIVE
            : ProtocolFrameCodec.FLAG_END_OF_STREAM);
    if (completion != null && completion.transactionActive()) {
      flags |= ProtocolFrameCodec.FLAG_TRANSACTION_ACTIVE;
    }
    return encode(
        target,
        type,
        requestId,
        status,
        flags,
        completion == null ? 0 : completion.affectedRows(),
        row.columnCount(),
        completion == null ? 0 : completion.commitSequence(),
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
    int valueBytes = ProtocolResponseValueEncoder.bytes(command, row, columns);
    if (valueBytes < 0) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int nullBitmapBytes = ProtocolResponseValueEncoder.nullBitmapBytes(columns);
    int payloadBytes = FIXED_BYTES + nullBitmapBytes
        + columns * Integer.BYTES + valueBytes;
    StatusCode encoded = ProtocolFrameWire.begin(
        target, type, requestId, payloadBytes, ProtocolFrameWire.FRAME_RESPONSE);
    if (!encoded.isOk()) {
      return encoded;
    }
    writeFixed(
        target, status, flags, rows, columns, commitSequence, key, returned,
        challengeHigh, challengeLow, nullBitmapBytes, 0);
    int offset = ProtocolResponseValueEncoder.writeNulls(
        target, ProtocolFrameCodec.HEADER_BYTES + FIXED_BYTES,
        command, row, columns);
    offset = ProtocolResponseValueEncoder.writeTypes(
        target, offset, command, row, columns);
    if (!ProtocolResponseValueEncoder.writeValues(
        target, offset, command, row, columns)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    return ProtocolResponseSegmenter.finish(target, type, requestId, payloadBytes);
  }

  private static int metadataBytes(ProtocolQueryMetadata query, int columns) {
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

  private static int writeMetadata(
      ByteBuffer target, ProtocolQueryMetadata query, int columns) {
    int offset = ProtocolFrameCodec.HEADER_BYTES + FIXED_BYTES;
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

  private static boolean matches(
      ProtocolQueryMetadata metadata, RowResult row, int columns) {
    if (!row.isAvailable()) return row.columnCount() == 0;
    if (row.columnCount() != columns) return false;
    for (int index = 0; index < columns; index++) {
      if (row.typeDescriptorAt(index) != metadata.typeDescriptorAt(index)) return false;
    }
    return true;
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
      int nullBitmapBytes,
      int metadataBytes) {
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
    target.putInt(offset + 56, nullBitmapBytes);
    target.putInt(offset + 60, metadataBytes);
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
