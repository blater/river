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
    if (metadataBytes < 0 || valueBytes < 0
        || !ProtocolQueryMetadataWriter.matches(metadata, row, columns)
        || queryActive && !rowAvailable
        || rowsReturned != (rowAvailable ? 1 : 0)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int rowNullBytes = rowAvailable
        ? ProtocolResponseValueEncoder.nullBitmapBytes(columns) : 0;
    int payloadBytes = ProtocolResponseFrameWriter.FIXED_BYTES
        + metadataBytes + rowNullBytes + valueBytes;
    StatusCode encoded = ProtocolFrameWire.begin(
        target, type, requestId, payloadBytes, ProtocolFrameWire.FRAME_RESPONSE);
    if (!encoded.isOk()) return encoded;

    int flags = ProtocolFrameCodec.FLAG_COLUMN_METADATA
        | (queryActive ? ProtocolFrameCodec.FLAG_QUERY_ACTIVE
            : ProtocolFrameCodec.FLAG_END_OF_STREAM)
        | (rowAvailable ? ProtocolFrameCodec.FLAG_ROW_AVAILABLE : 0);
    if (completion != null && completion.transactionActive()) {
      flags |= ProtocolFrameCodec.FLAG_TRANSACTION_ACTIVE;
    }
    ProtocolResponseFrameWriter.writeFixed(
        target, status, flags,
        completion == null ? 0 : completion.affectedRows(), columns,
        completion == null ? 0 : completion.commitSequence(),
        rowAvailable ? row.key() : 0, rowsReturned, 0, 0,
        rowNullBytes, metadataBytes);
    int offset = ProtocolQueryMetadataWriter.write(target, metadata, columns);
    if (rowAvailable) {
      offset = ProtocolResponseValueEncoder.writeNulls(target, offset, null, row, columns);
      if (!ProtocolResponseValueEncoder.writeValues(target, offset, null, row, columns)) {
        return ProtocolFrameWire.invalidTarget(target);
      }
    }
    return ProtocolResponseSegmenter.finish(target, type, requestId, payloadBytes);
  }

  private static boolean validTarget(
      ProtocolMessageType type,
      StatusCode status,
      ProtocolQueryMetadata metadata,
      RowResult row,
      CommandResult completion,
      boolean queryActive) {
    if ((type != ProtocolMessageType.BEGIN_QUERY
            && type != ProtocolMessageType.BEGIN_PREPARED_QUERY)
        || status == null || status.isOk() && (metadata == null
            || row == null || queryActive == (completion != null))) {
      return false;
    }
    return status.isOk() || (metadata == null && row == null && completion == null);
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
