package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ProgramOpenResult;

/** Strict decoder for the PREPARE_PROGRAM handle response. */
public final class ProtocolProgramOpenResponseDecoder {
  private StatusCode status;

  public StatusCode decode(ProtocolFrame frame, ProgramOpenResult result) {
    status = null;
    if (frame == null || !frame.isResponse() || result == null
        || frame.type() != ProtocolMessageType.PREPARE_PROGRAM
        || frame.payloadBytes() != ProtocolProgramOpenResponseEncoder.BYTES) {
      resultReset(result);
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int offset = frame.payloadOffset();
    java.nio.ByteBuffer source = frame.source();
    StatusCode decoded = decodeFields(source, offset, result);
    if (!decoded.isOk()) result.reset();
    return decoded;
  }

  public StatusCode status() { return status; }
  public void reset() { status = null; }

  private StatusCode decodeFields(
      java.nio.ByteBuffer source, int offset, ProgramOpenResult result) {
    if (source.getInt(offset) != ProtocolProgramOpenResponseEncoder.FORMAT
        || source.getInt(offset + 20) != 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int index = offset + 24; index < offset + ProtocolProgramOpenResponseEncoder.BYTES;
        index++) {
      if (source.get(index) != 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode response = ProtocolResponsePayloadDecoder.statusFromStableCode(
        source.getInt(offset + 4));
    long handle = source.getLong(offset + 8);
    int arguments = source.getInt(offset + 16);
    if (response == null || arguments < 0
        || response.isOk() && (handle <= 0 || result.complete(handle, arguments) != StatusCode.OK)
        || !response.isOk() && (handle != 0 || arguments != 0)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    status = response;
    return StatusCode.OK;
  }

  private static void resultReset(ProgramOpenResult result) {
    if (result != null) result.reset();
  }
}
