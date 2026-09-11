package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.RowResult;
import java.nio.ByteBuffer;

/** Encodes bounded response metadata and values into caller-owned buffers. */
final class ProtocolResponseEncoder {
  StatusCode encodeStatus(
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

  StatusCode encodeHello(
      ByteBuffer target,
      long requestId,
      StatusCode status,
      long challengeHigh,
      long challengeLow) {
    return ProtocolResponseFrameWriter.encode(
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
    return ProtocolResponseFrameWriter.encode(
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
    return ProtocolQueryOpenResponseEncoder.encode(
        target, type, requestId, status, metadata, row,
        rowsReturned, completion, queryActive);
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
    return ProtocolResponseFrameWriter.encode(
        target, type, requestId, status, flags,
        command.affectedRows(), command.columnCount(), command.commitSequence(),
        command.key(), 0, 0, 0, command, null);
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
    return ProtocolResponseFrameWriter.encode(
        target, type, requestId, status, flags,
        completion == null ? 0 : completion.affectedRows(), row.columnCount(),
        completion == null ? 0 : completion.commitSequence(), row.key(), rowsReturned,
        0, 0, null, row);
  }
}
