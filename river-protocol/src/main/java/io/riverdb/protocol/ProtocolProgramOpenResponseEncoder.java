package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ProgramOpenResult;
import java.nio.ByteBuffer;

/** Encodes the handle and argument shape returned by PREPARE_PROGRAM. */
final class ProtocolProgramOpenResponseEncoder {
  static final int FORMAT = 1;
  static final int BYTES = 64;

  StatusCode encode(
      ByteBuffer target, long requestId, StatusCode status, ProgramOpenResult opened) {
    if (target == null || requestId <= 0 || status == null
        || status.isOk() && (opened == null || opened.handle() <= 0)) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    StatusCode encoded = ProtocolFrameWire.begin(
        target, ProtocolMessageType.PREPARE_PROGRAM, requestId, BYTES,
        ProtocolFrameWire.FRAME_RESPONSE);
    if (!encoded.isOk()) return encoded;
    int offset = ProtocolFrameCodec.HEADER_BYTES;
    target.putInt(offset, FORMAT);
    target.putInt(offset + 4, status.stableCode());
    target.putLong(offset + 8, status.isOk() ? opened.handle() : 0);
    target.putInt(offset + 16, status.isOk() ? opened.requiredArgumentSlots() : 0);
    target.putInt(offset + 20, 0);
    for (int index = offset + 24; index < offset + BYTES; index++) target.put(index, (byte) 0);
    target.position(0);
    target.limit(ProtocolFrameCodec.HEADER_BYTES + BYTES);
    return StatusCode.OK;
  }
}
