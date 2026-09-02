package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Reassembles bounded response frames before publishing a decoded result. */
final class ProtocolContinuedResponseDecoder {
  private final ProtocolFrameHeader header = new ProtocolFrameHeader();
  private final ProtocolResponseAssembly assembly = new ProtocolResponseAssembly();
  private StatusCode inspectedStatus = StatusCode.OK;

  StatusCode decode(ProtocolFrameCodec codec, ProtocolResponseDecoder decoder,
      ByteBuffer source, ProtocolFrame frame, ProtocolResponse result) {
    StatusCode status = codec.inspectResponseHeader(source, header);
    if (!status.isOk() || !header.isContinuation()) {
      return status.isOk() ? decoder.decode(source, frame, result) : status;
    }
    assembly.reset();
    status = acceptFrames(codec, source);
    return status.isOk()
        ? decoder.decodeAssembled(assembly.source(), frame, result) : status;
  }

  StatusCode decodeProgram(
      ProtocolFrameCodec codec, ProtocolProgramResultDecoder decoder,
      ByteBuffer source, ProtocolFrame frame,
      io.riverdb.engine.api.TransactionProgramResult result) {
    StatusCode status = codec.inspectResponseHeader(source, header);
    if (!status.isOk() || !header.isContinuation()) {
      return status.isOk() ? decoder.decode(source, frame, result) : status;
    }
    assembly.reset();
    status = acceptFrames(codec, source);
    return status.isOk()
        ? decoder.decodeAssembled(assembly.source(), frame, result) : status;
  }

  private StatusCode acceptFrames(ProtocolFrameCodec codec, ByteBuffer source) {
    int originalPosition = source.position();
    int originalLimit = source.limit();
    int offset = originalPosition;
    while (offset < originalLimit) {
      source.position(offset);
      source.limit(originalLimit);
      int frameBytes = inspectFrame(codec, source);
      if (frameBytes < 0) return restore(
          source, originalPosition, originalLimit, inspectedStatus);
      source.limit(offset + frameBytes);
      StatusCode status = assembly.accept(source, header);
      if (!status.isOk()) return restore(source, originalPosition, originalLimit, status);
      offset += frameBytes;
    }
    StatusCode status = assembly.isComplete() && offset == originalLimit
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
    return restore(source, originalPosition, originalLimit, status);
  }

  private int inspectFrame(ProtocolFrameCodec codec, ByteBuffer source) {
    StatusCode status = codec.inspectResponseHeader(source, header);
    int bytes = ProtocolFrameCodec.HEADER_BYTES + header.payloadBytes();
    inspectedStatus = status.isOk() && source.remaining() < bytes
        ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    return inspectedStatus.isOk() ? bytes : -1;
  }

  private static StatusCode restore(
      ByteBuffer source, int position, int limit, StatusCode status) {
    source.limit(limit);
    source.position(position);
    return status;
  }
}
