package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RowResult;
import java.nio.ByteBuffer;

/** Encodes query-open metadata and its optional first row. */
final class ProtocolQueryOpenResponseEncoder {
  private ProtocolQueryOpenResponseEncoder() { }

  static StatusCode encode(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      ProtocolQueryMetadata metadata,
      RowResult row,
      long rowsReturned,
      CommandResult completion,
      boolean queryActive) {
    if (!validTarget(type, status, metadata, row, completion, queryActive)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    if (!status.isOk()) return encodeStatus(target, type, requestId, status, queryActive);

    int columns = metadata.columnCount();
    int metadataBytes = ProtocolQueryMetadataWriter.bytes(metadata, columns);
    boolean rowAvailable = row.isAvailable();
    int valueBytes = rowAvailable
        ? ProtocolResponseValueEncoder.bytes(null, row, columns) : 0;
    if (!validPayload(
        metadata, row, columns, metadataBytes, valueBytes,
        rowAvailable, queryActive, rowsReturned)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int rowNullBytes = rowAvailable
        ? ProtocolResponseValueEncoder.nullBitmapBytes(columns) : 0;
    int payloadBytes = ProtocolResponseFrameWriter.FIXED_BYTES
        + metadataBytes + rowNullBytes + valueBytes;
    StatusCode encoded = ProtocolFrameWire.begin(
        target, type, requestId, payloadBytes, ProtocolFrameWire.FRAME_RESPONSE);
    if (!encoded.isOk()) return encoded;

    writeHeader(target, status, queryActive, rowAvailable, completion, row,
        columns, rowsReturned, rowNullBytes, metadataBytes);
    int offset = ProtocolQueryMetadataWriter.write(target, metadata, columns);
    if (!writeRow(target, offset, row, columns, rowAvailable)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    return ProtocolResponseSegmenter.finish(target, type, requestId, payloadBytes);
  }

  private static boolean validPayload(
      ProtocolQueryMetadata metadata,
      RowResult row,
      int columns,
      int metadataBytes,
      int valueBytes,
      boolean rowAvailable,
      boolean queryActive,
      long rowsReturned) {
    if (metadataBytes < 0 || valueBytes < 0) return false;
    if (!ProtocolQueryMetadataWriter.matches(metadata, row, columns)) return false;
    if (queryActive && !rowAvailable) return false;
    return rowsReturned == (rowAvailable ? 1 : 0);
  }

  private static void writeHeader(
      ByteBuffer target, StatusCode status, boolean queryActive, boolean rowAvailable,
      CommandResult completion, RowResult row, int columns, long rowsReturned,
      int rowNullBytes, int metadataBytes) {
    int flags = ProtocolFrameCodec.FLAG_COLUMN_METADATA;
    flags |= queryActive
        ? ProtocolFrameCodec.FLAG_QUERY_ACTIVE
        : ProtocolFrameCodec.FLAG_END_OF_STREAM;
    if (rowAvailable) flags |= ProtocolFrameCodec.FLAG_ROW_AVAILABLE;
    if (completion != null && completion.transactionActive()) {
      flags |= ProtocolFrameCodec.FLAG_TRANSACTION_ACTIVE;
    }
    ProtocolResponseFrameWriter.writeFixed(
        target, status, flags,
        completion == null ? 0 : completion.affectedRows(), columns,
        completion == null ? 0 : completion.commitSequence(),
        rowAvailable ? row.key() : 0, rowsReturned, 0, 0,
        rowNullBytes, metadataBytes);
  }

  private static boolean writeRow(
      ByteBuffer target, int offset, RowResult row, int columns, boolean rowAvailable) {
    if (!rowAvailable) return true;
    int next = ProtocolResponseValueEncoder.writeNulls(target, offset, null, row, columns);
    return ProtocolResponseValueEncoder.writeValues(target, next, null, row, columns);
  }

  private static boolean validTarget(
      ProtocolMessageType type,
      StatusCode status,
      ProtocolQueryMetadata metadata,
      RowResult row,
      CommandResult completion,
      boolean queryActive) {
    if (!isQueryOpenType(type) || status == null) return false;
    if (!status.isOk()) return metadata == null && row == null && completion == null;
    if (metadata == null || row == null) return false;
    return queryActive != (completion != null);
  }

  private static boolean isQueryOpenType(ProtocolMessageType type) {
    return type == ProtocolMessageType.BEGIN_QUERY
        || type == ProtocolMessageType.BEGIN_PREPARED_QUERY;
  }

  private static StatusCode encodeStatus(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      boolean queryActive) {
    return ProtocolResponseFrameWriter.encode(
        target, type, requestId, status,
        queryActive ? ProtocolFrameCodec.FLAG_QUERY_ACTIVE : 0,
        0, 0, 0, 0, 0, 0, 0, null, null);
  }
}
