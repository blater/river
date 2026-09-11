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
    int minimumMetadata = ProtocolResponseNullBitmap.bytes(columns)
        + columns * (Integer.BYTES + 1);
    int variableBytes = frame.payloadBytes() - ProtocolResponseFrameWriter.FIXED_BYTES;
    return (frame.type() == ProtocolMessageType.BEGIN_QUERY
            || frame.type() == ProtocolMessageType.BEGIN_PREPARED_QUERY)
        && status.isOk()
        && !preparedQuery && columns > 0 && active != endOfStream
        && (!active || row)
        && (!active || (flags & ProtocolFrameCodec.FLAG_TRANSACTION_ACTIVE) == 0)
        && returned == (row ? 1 : 0) && (row || key == 0)
        && nullBytes == (row ? ProtocolResponseNullBitmap.bytes(columns) : 0)
        && reserved >= minimumMetadata && reserved <= variableBytes - nullBytes;
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
