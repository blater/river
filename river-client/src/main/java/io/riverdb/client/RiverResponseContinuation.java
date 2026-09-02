package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolMessageType;
import java.io.IOException;

/** Reads the remaining physical frames of one continued response. */
final class RiverResponseContinuation {
  private RiverResponseContinuation() { }

  static StatusCode read(RiverClientConnection connection, ProtocolMessageType type,
      long requestId, int firstPayloadBytes) throws IOException {
    int logicalBytes = connection.responseBuffer.getInt(ProtocolFrameCodec.HEADER_BYTES);
    int wireBytes = ProtocolFrameCodec.continuedResponseWireBytes(logicalBytes);
    if (wireBytes == 0) return StatusCode.CORRUPTION;
    StatusCode status = connection.reserveResponseBytes(wireBytes);
    if (!status.isOk()) return status;
    int offset = ProtocolFrameCodec.HEADER_BYTES + firstPayloadBytes;
    while (offset < wireBytes) {
      status = readFrame(connection, type, requestId, offset, wireBytes);
      if (!status.isOk()) return status;
      offset += ProtocolFrameCodec.HEADER_BYTES + connection.responseHeader.payloadBytes();
    }
    if (offset != wireBytes || !connection.responseHeader.isFinalSegment()) {
      return StatusCode.CORRUPTION;
    }
    connection.responseBuffer.position(0);
    connection.responseBuffer.limit(wireBytes);
    return StatusCode.OK;
  }

  private static StatusCode readFrame(RiverClientConnection connection, ProtocolMessageType type,
      long requestId, int offset, int wireBytes) throws IOException {
    if (!RiverClientConnection.readExact(
        connection.input, connection.responseBytes, offset, ProtocolFrameCodec.HEADER_BYTES)) {
      return StatusCode.IO_FAILURE;
    }
    connection.responseBuffer.limit(connection.responseBuffer.capacity());
    connection.responseBuffer.position(offset);
    connection.responseBuffer.limit(offset + ProtocolFrameCodec.HEADER_BYTES);
    StatusCode status = connection.codec.inspectResponseHeader(
        connection.responseBuffer, connection.responseHeader);
    if (!status.isOk() || !connection.responseHeader.isContinuation()
        || connection.responseHeader.typeWireCode() != type.wireCode()
        || connection.responseHeader.requestId() != requestId) return StatusCode.CORRUPTION;
    int payload = connection.responseHeader.payloadBytes();
    if (offset > wireBytes - ProtocolFrameCodec.HEADER_BYTES - payload) {
      return StatusCode.CORRUPTION;
    }
    return RiverClientConnection.readExact(connection.input, connection.responseBytes,
        offset + ProtocolFrameCodec.HEADER_BYTES, payload)
            ? StatusCode.OK : StatusCode.IO_FAILURE;
  }
}
