package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Validates and decodes the query-open response metadata and optional first row. */
final class ProtocolQueryOpenResponseDecoder {
  private ProtocolQueryOpenResponseDecoder() { }

  static boolean validHeader(
      ProtocolFrame frame, StatusCode status, int flags, int columns, long key, long returned,
      int nullBytes, int reserved, boolean preparedQuery) {
    boolean row = (flags & ProtocolFrameCodec.FLAG_ROW_AVAILABLE) != 0;
    boolean active = (flags & ProtocolFrameCodec.FLAG_QUERY_ACTIVE) != 0;
    boolean endOfStream = (flags & ProtocolFrameCodec.FLAG_END_OF_STREAM) != 0;
    if (!validHeaderPrefix(frame, status, columns, preparedQuery)) return false;
    if (active == endOfStream || active && !row) return false;
    if (active && (flags & ProtocolFrameCodec.FLAG_TRANSACTION_ACTIVE) != 0) return false;
    if (!validRowHeader(row, returned, key, nullBytes, columns)) return false;
    int minimumMetadata = ProtocolResponseNullBitmap.bytes(columns)
        + columns * (Integer.BYTES + 1);
    int variableBytes = frame.payloadBytes() - ProtocolResponseFrameWriter.FIXED_BYTES;
    return reserved >= minimumMetadata && reserved <= variableBytes - nullBytes;
  }

  private static boolean validHeaderPrefix(
      ProtocolFrame frame, StatusCode status, int columns, boolean preparedQuery) {
    if (!status.isOk()) return false;
    if (preparedQuery || columns <= 0) return false;
    ProtocolMessageType type = frame.type();
    return type == ProtocolMessageType.BEGIN_QUERY
        || type == ProtocolMessageType.BEGIN_PREPARED_QUERY;
  }

  private static boolean validRowHeader(
      boolean row, long returned, long key, int nullBytes, int columns) {
    if (row) {
      return returned == 1 && nullBytes == ProtocolResponseNullBitmap.bytes(columns);
    }
    return returned == 0 && key == 0 && nullBytes == 0;
  }

  static StatusCode decode(
      ByteBuffer bytes,
      int offset,
      int end,
      int columns,
      int rowNullBytes,
      int metadataBytes,
      boolean row,
      ProtocolResponse result) {
    int nullableBytes = ProtocolResponseNullBitmap.bytes(columns);
    int metadataEnd = offset + metadataBytes;
    StatusCode status = ProtocolResponseNullBitmap.decodeNullable(
        bytes, offset, columns, nullableBytes, result);
    if (!status.isOk()) return status;
    int nameOffset = ProtocolResponseValueDecoder.types(
        bytes, offset + nullableBytes, metadataEnd, columns, result);
    if (nameOffset < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    status = ProtocolResponseValueDecoder.metadata(
        bytes, nameOffset, metadataEnd, columns, result);
    if (!status.isOk()) return status;
    if (!row) return result.beginNulls(columns);
    status = ProtocolResponseNullBitmap.decode(
        bytes, metadataEnd, columns, rowNullBytes, result);
    return status.isOk()
        ? ProtocolResponseValueDecoder.values(
            bytes, metadataEnd + rowNullBytes, end, columns, result)
        : status;
  }
}
