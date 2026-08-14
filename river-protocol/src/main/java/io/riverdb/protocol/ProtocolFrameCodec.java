package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RowResult;
import java.nio.ByteBuffer;

/** Bounded v2 framing over caller-owned buffers. */
public final class ProtocolFrameCodec {
  public static final int VERSION = 2;
  public static final int HEADER_BYTES = 32;
  public static final int MAXIMUM_PAYLOAD_BYTES = 16 * 1024;
  public static final int MAXIMUM_FRAME_BYTES = HEADER_BYTES + MAXIMUM_PAYLOAD_BYTES;
  public static final int MAXIMUM_COLUMN_NAME_BYTES = 64;
  public static final int MAXIMUM_RESPONSE_BYTES = HEADER_BYTES + 64
      + CommandResult.MAXIMUM_COLUMNS
          * (Integer.BYTES + Short.BYTES + Utf8Text.MAXIMUM_BYTES);
  public static final int FLAG_ROW_AVAILABLE = 1;
  public static final int FLAG_TRANSACTION_ACTIVE = 1 << 1;
  public static final int FLAG_QUERY_ACTIVE = 1 << 2;
  public static final int FLAG_COLUMN_METADATA = 1 << 3;

  private final ProtocolResponseEncoder responses = new ProtocolResponseEncoder();
  private final ProtocolResponseDecoder responseDecoder =
      new ProtocolResponseDecoder();

  /** Inspects exactly the request metadata needed before reading its payload. */
  public StatusCode inspectRequestHeader(
      ByteBuffer source, ProtocolFrameHeader result) {
    return ProtocolFrameWire.inspect(
        source, result, ProtocolFrameWire.ROLE_REQUEST);
  }

  /** Inspects exactly the response metadata needed before reading its payload. */
  public StatusCode inspectResponseHeader(
      ByteBuffer source, ProtocolFrameHeader result) {
    return ProtocolFrameWire.inspect(
        source, result, ProtocolFrameWire.ROLE_RESPONSE);
  }

  public StatusCode decode(ByteBuffer source, ProtocolFrame result) {
    return ProtocolFrameWire.decode(
        source, result, ProtocolFrameWire.ROLE_REQUEST);
  }

  public StatusCode encodeRequest(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId) {
    if (type == null || type.requiresPayload()) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    return ProtocolFrameWire.begin(target, type, requestId, 0, 0);
  }

  public StatusCode encodeTextRequest(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      String text) {
    if (target == null || type == null || !type.hasTextPayload()
        || requestId <= 0 || text == null || text.isEmpty()) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    int bytes = utf8Length(text);
    if (bytes < 0) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    if (bytes > MAXIMUM_PAYLOAD_BYTES) {
      ProtocolFrameWire.empty(target);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = ProtocolFrameWire.begin(
        target, type, requestId, bytes, 0);
    if (!status.isOk()) {
      return status;
    }
    writeText(target, text);
    target.position(0);
    target.limit(HEADER_BYTES + bytes);
    return StatusCode.OK;
  }

  public StatusCode encodeBinaryRequest(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      byte[] payload,
      int payloadBytes) {
    if (target == null || type == null || !type.requiresPayload()
        || type.hasTextPayload() || requestId <= 0 || payload == null
        || payloadBytes <= 0 || payloadBytes > payload.length) {
      return ProtocolFrameWire.invalidTarget(target);
    }
    if (payloadBytes > MAXIMUM_PAYLOAD_BYTES) {
      ProtocolFrameWire.empty(target);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = ProtocolFrameWire.begin(
        target, type, requestId, payloadBytes, 0);
    if (!status.isOk()) {
      return status;
    }
    for (int index = 0; index < payloadBytes; index++) {
      target.put(HEADER_BYTES + index, payload[index]);
    }
    target.position(0);
    target.limit(HEADER_BYTES + payloadBytes);
    return StatusCode.OK;
  }

  public StatusCode encodeStatusResponse(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      boolean queryActive) {
    return responses.encodeStatus(target, type, requestId, status, queryActive);
  }

  public StatusCode encodeHelloResponse(
      ByteBuffer target,
      long requestId,
      StatusCode status,
      long challengeHigh,
      long challengeLow) {
    return responses.encodeHello(
        target, requestId, status, challengeHigh, challengeLow);
  }

  public StatusCode encodeQueryOpenResponse(
      ByteBuffer target,
      long requestId,
      StatusCode status,
      RiverQuery query) {
    return responses.encodeQueryOpen(target, requestId, status, query);
  }

  public StatusCode encodeCommandResponse(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      CommandResult command,
      boolean queryActive) {
    return responses.encodeCommand(
        target, type, requestId, status, command, queryActive);
  }

  public StatusCode encodeRowResponse(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      RowResult row,
      long rowsReturned,
      boolean queryActive) {
    return responses.encodeRow(
        target, type, requestId, status, row, rowsReturned, queryActive);
  }

  public StatusCode decodeResponse(
      ByteBuffer source,
      ProtocolFrame frame,
      ProtocolResponse result) {
    return responseDecoder.decode(source, frame, result);
  }

  private static void writeText(ByteBuffer target, String text) {
    int output = HEADER_BYTES;
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character < 0x80) {
        target.put(output++, (byte) character);
      } else if (character < 0x800) {
        target.put(output++, (byte) (0xc0 | character >>> 6));
        target.put(output++, (byte) (0x80 | character & 0x3f));
      } else if (Character.isHighSurrogate(character)) {
        int scalar = Character.toCodePoint(character, text.charAt(++index));
        target.put(output++, (byte) (0xf0 | scalar >>> 18));
        target.put(output++, (byte) (0x80 | scalar >>> 12 & 0x3f));
        target.put(output++, (byte) (0x80 | scalar >>> 6 & 0x3f));
        target.put(output++, (byte) (0x80 | scalar & 0x3f));
      } else {
        target.put(output++, (byte) (0xe0 | character >>> 12));
        target.put(output++, (byte) (0x80 | character >>> 6 & 0x3f));
        target.put(output++, (byte) (0x80 | character & 0x3f));
      }
    }
  }

  private static int utf8Length(String text) {
    int bytes = 0;
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character < 0x80) {
        bytes++;
      } else if (character < 0x800) {
        bytes += 2;
      } else if (Character.isHighSurrogate(character)) {
        if (index + 1 >= text.length()
            || !Character.isLowSurrogate(text.charAt(index + 1))) {
          return -1;
        }
        bytes += 4;
        index++;
      } else if (Character.isLowSurrogate(character)) {
        return -1;
      } else {
        bytes += 3;
      }
      if (bytes > MAXIMUM_PAYLOAD_BYTES) {
        return bytes;
      }
    }
    return bytes;
  }
}
