package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Shared v2 frame-header validation and emission. */
final class ProtocolFrameWire {
  static final int ROLE_REQUEST = 1;
  static final int ROLE_RESPONSE = 2;
  static final int FRAME_RESPONSE = 1;
  private static final int MAGIC = 0x52495652;
  private static final int RESPONSE_FIXED_BYTES = 64;

  private ProtocolFrameWire() { }

  static StatusCode inspect(
      ByteBuffer source, ProtocolFrameHeader result, int role) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = validateHeader(source, role);
    if (!status.isOk()) {
      return status;
    }
    int start = source.position();
    int flags = headerInt(source, start + 12);
    result.complete(
        headerInt(source, start + 8),
        headerLong(source, start + 16),
        headerInt(source, start + 24),
        (flags & FRAME_RESPONSE) != 0);
    return StatusCode.OK;
  }

  static StatusCode decode(
      ByteBuffer source, ProtocolFrame result, int role) {
    if (source == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int start = source.position();
    int available = source.remaining();
    StatusCode status = validateHeader(source, role);
    if (!status.isOk()) {
      return status;
    }
    source.order(ByteOrder.BIG_ENDIAN);
    ProtocolMessageType type = ProtocolMessageType.fromWireCode(
        headerInt(source, start + 8));
    int flags = headerInt(source, start + 12);
    long requestId = headerLong(source, start + 16);
    int payloadBytes = headerInt(source, start + 24);
    if (available != ProtocolFrameCodec.HEADER_BYTES + payloadBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.complete(
        source,
        type,
        requestId,
        start + ProtocolFrameCodec.HEADER_BYTES,
        payloadBytes,
        (flags & FRAME_RESPONSE) != 0);
    return StatusCode.OK;
  }

  static StatusCode begin(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      int payloadBytes,
      int flags) {
    if (target == null || target.isReadOnly() || type == null
        || requestId <= 0 || payloadBytes < 0) {
      return invalidTarget(target);
    }
    int required = ProtocolFrameCodec.HEADER_BYTES + payloadBytes;
    if (target.capacity() < required) {
      empty(target);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    target.clear();
    target.order(ByteOrder.BIG_ENDIAN);
    target.putInt(0, MAGIC);
    target.putInt(4, ProtocolFrameCodec.VERSION);
    target.putInt(8, type.wireCode());
    target.putInt(12, flags);
    target.putLong(16, requestId);
    target.putInt(24, payloadBytes);
    target.putInt(28, 0);
    target.position(0);
    target.limit(required);
    return StatusCode.OK;
  }

  static StatusCode invalidTarget(ByteBuffer target) {
    if (target != null) {
      empty(target);
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  static void empty(ByteBuffer target) {
    target.clear();
    target.limit(0);
  }

  private static StatusCode validateHeader(ByteBuffer source, int role) {
    if (source == null || source.remaining() < ProtocolFrameCodec.HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int start = source.position();
    if (headerInt(source, start) != MAGIC) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (headerInt(source, start + 4) != ProtocolFrameCodec.VERSION) {
      return StatusCode.CONFLICT;
    }
    ProtocolMessageType type = ProtocolMessageType.fromWireCode(
        headerInt(source, start + 8));
    int flags = headerInt(source, start + 12);
    long requestId = headerLong(source, start + 16);
    int payloadBytes = headerInt(source, start + 24);
    int reserved = headerInt(source, start + 28);
    boolean response = (flags & FRAME_RESPONSE) != 0;
    if (type == null || (flags & ~FRAME_RESPONSE) != 0
        || role == ROLE_REQUEST && response
        || role == ROLE_RESPONSE && !response
        || requestId <= 0 || payloadBytes < 0 || reserved != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int maximumPayload = role == ROLE_REQUEST
        ? ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES
        : ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES
            - ProtocolFrameCodec.HEADER_BYTES;
    if (payloadBytes > maximumPayload) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (role == ROLE_REQUEST && type.requiresPayload() != (payloadBytes > 0)
        || role == ROLE_RESPONSE && payloadBytes < RESPONSE_FIXED_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }

  private static int headerInt(ByteBuffer source, int offset) {
    return (source.get(offset) & 0xff) << 24
        | (source.get(offset + 1) & 0xff) << 16
        | (source.get(offset + 2) & 0xff) << 8
        | source.get(offset + 3) & 0xff;
  }

  private static long headerLong(ByteBuffer source, int offset) {
    return (long) headerInt(source, offset) << 32
        | headerInt(source, offset + Integer.BYTES) & 0xffff_ffffL;
  }
}
