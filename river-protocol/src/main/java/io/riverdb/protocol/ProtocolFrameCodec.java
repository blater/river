package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RowResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded v1 framing and value encoding over caller-owned buffers. */
public final class ProtocolFrameCodec {
  public static final int VERSION = 1;
  public static final int HEADER_BYTES = 32;
  public static final int MAXIMUM_PAYLOAD_BYTES = 16 * 1024;
  public static final int MAXIMUM_FRAME_BYTES = HEADER_BYTES + MAXIMUM_PAYLOAD_BYTES;
  public static final int MAXIMUM_COLUMN_NAME_BYTES = 64;
  public static final int MAXIMUM_RESPONSE_BYTES = HEADER_BYTES + 72
      + CommandResult.MAXIMUM_COLUMNS * (1 + MAXIMUM_COLUMN_NAME_BYTES);
  public static final int FLAG_ROW_AVAILABLE = 1;
  public static final int FLAG_TRANSACTION_ACTIVE = 1 << 1;
  public static final int FLAG_QUERY_ACTIVE = 1 << 2;
  public static final int FLAG_COLUMN_METADATA = 1 << 3;

  private static final int MAGIC = 0x52495652;
  private static final int FRAME_RESPONSE = 1;
  private static final int RESPONSE_FIXED_BYTES = 72;

  public StatusCode decode(ByteBuffer source, ProtocolFrame result) {
    if (source == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int start = source.position();
    int available = source.remaining();
    if (available < HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    source.order(ByteOrder.BIG_ENDIAN);
    if (source.getInt(start) != MAGIC) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (source.getInt(start + 4) != VERSION) {
      return StatusCode.CONFLICT;
    }
    ProtocolMessageType type = ProtocolMessageType.fromWireCode(source.getInt(start + 8));
    int frameFlags = source.getInt(start + 12);
    long requestId = source.getLong(start + 16);
    int payloadBytes = source.getInt(start + 24);
    int reserved = source.getInt(start + 28);
    if (type == null
        || (frameFlags & ~FRAME_RESPONSE) != 0
        || requestId <= 0
        || payloadBytes < 0
        || reserved != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (payloadBytes > MAXIMUM_PAYLOAD_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (available != HEADER_BYTES + payloadBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.complete(
        source,
        type,
        requestId,
        start + HEADER_BYTES,
        payloadBytes,
        (frameFlags & FRAME_RESPONSE) != 0);
    return StatusCode.OK;
  }

  public StatusCode encodeRequest(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId) {
    if (type == null || type.requiresPayload()) {
      return invalidTarget(target);
    }
    return beginFrame(target, type, requestId, 0, 0);
  }

  public StatusCode encodeTextRequest(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      String text) {
    if (target == null || type == null || !type.hasTextPayload()
        || requestId <= 0 || text == null || text.isEmpty()) {
      return invalidTarget(target);
    }
    int bytes = utf8Length(text);
    if (bytes < 0) {
      return invalidTarget(target);
    }
    if (bytes > MAXIMUM_PAYLOAD_BYTES) {
      empty(target);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = beginFrame(target, type, requestId, bytes, 0);
    if (!status.isOk()) {
      return status;
    }
    int output = HEADER_BYTES;
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character < 0x80) {
        target.put(output++, (byte) character);
      } else if (character < 0x800) {
        target.put(output++, (byte) (0xc0 | character >>> 6));
        target.put(output++, (byte) (0x80 | character & 0x3f));
      } else if (Character.isHighSurrogate(character)) {
        char low = text.charAt(++index);
        int codePoint = Character.toCodePoint(character, low);
        target.put(output++, (byte) (0xf0 | codePoint >>> 18));
        target.put(output++, (byte) (0x80 | codePoint >>> 12 & 0x3f));
        target.put(output++, (byte) (0x80 | codePoint >>> 6 & 0x3f));
        target.put(output++, (byte) (0x80 | codePoint & 0x3f));
      } else {
        target.put(output++, (byte) (0xe0 | character >>> 12));
        target.put(output++, (byte) (0x80 | character >>> 6 & 0x3f));
        target.put(output++, (byte) (0x80 | character & 0x3f));
      }
    }
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
    if (target == null
        || type == null
        || !type.requiresPayload()
        || type.hasTextPayload()
        || requestId <= 0
        || payload == null
        || payloadBytes <= 0
        || payloadBytes > payload.length) {
      return invalidTarget(target);
    }
    if (payloadBytes > MAXIMUM_PAYLOAD_BYTES) {
      empty(target);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = beginFrame(target, type, requestId, payloadBytes, 0);
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
    return encodeResponse(
        target,
        type,
        requestId,
        status,
        queryActive ? FLAG_QUERY_ACTIVE : 0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        null,
        null);
  }

  public StatusCode encodeHelloResponse(
      ByteBuffer target,
      long requestId,
      StatusCode status,
      long challengeHigh,
      long challengeLow) {
    return encodeResponse(
        target,
        ProtocolMessageType.HELLO,
        requestId,
        status,
        0,
        0,
        0,
        0,
        0,
        0,
        challengeHigh,
        challengeLow,
        null,
        null);
  }

  public StatusCode encodeQueryOpenResponse(
      ByteBuffer target,
      long requestId,
      StatusCode status,
      RiverQuery query) {
    if (status == null
        || status.isOk() && (query == null || !query.isActive())
        || !status.isOk() && query != null) {
      return invalidTarget(target);
    }
    if (!status.isOk()) {
      return encodeResponse(
          target,
          ProtocolMessageType.BEGIN_QUERY,
          requestId,
          status,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          null,
          null);
    }
    int columns = query.columnCount();
    if (columns <= 0 || columns > CommandResult.MAXIMUM_COLUMNS) {
      return invalidTarget(target);
    }
    int metadataBytes = 0;
    long varcharMask = 0;
    for (int index = 0; index < columns; index++) {
      CharSequence name = query.columnName(index);
      if (!validColumnName(name)) {
        return invalidTarget(target);
      }
      metadataBytes += 1 + name.length();
      if (query.columnIsVarchar(index)) {
        varcharMask |= 1L << index;
      }
    }
    int payloadBytes = RESPONSE_FIXED_BYTES + metadataBytes;
    StatusCode encoded = beginFrame(
        target,
        ProtocolMessageType.BEGIN_QUERY,
        requestId,
        payloadBytes,
        FRAME_RESPONSE);
    if (!encoded.isOk()) {
      return encoded;
    }
    writeResponseFixed(
        target,
        status,
        FLAG_QUERY_ACTIVE | FLAG_COLUMN_METADATA,
        0,
        columns,
        0,
        0,
        0,
        0,
        0,
        0,
        varcharMask);
    int offset = HEADER_BYTES + RESPONSE_FIXED_BYTES;
    for (int index = 0; index < columns; index++) {
      CharSequence name = query.columnName(index);
      target.put(offset++, (byte) name.length());
      for (int character = 0; character < name.length(); character++) {
        target.put(offset++, (byte) name.charAt(character));
      }
    }
    target.position(0);
    target.limit(HEADER_BYTES + payloadBytes);
    return StatusCode.OK;
  }

  public StatusCode encodeCommandResponse(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      CommandResult command,
      boolean queryActive) {
    int flags = queryActive ? FLAG_QUERY_ACTIVE : 0;
    if (command.transactionActive()) {
      flags |= FLAG_TRANSACTION_ACTIVE;
    }
    if (command.rowAvailable()) {
      flags |= FLAG_ROW_AVAILABLE;
    }
    return encodeResponse(
        target,
        type,
        requestId,
        status,
        flags,
        command.affectedRows(),
        command.columnCount(),
        command.commitSequence(),
        command.key(),
        0,
        0,
        0,
        command,
        null);
  }

  public StatusCode encodeRowResponse(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      RowResult row,
      long rowsReturned,
      boolean queryActive) {
    int flags = (row.isAvailable() ? FLAG_ROW_AVAILABLE : 0)
        | (queryActive ? FLAG_QUERY_ACTIVE : 0);
    return encodeResponse(
        target,
        type,
        requestId,
        status,
        flags,
        0,
        row.columnCount(),
        0,
        row.key(),
        rowsReturned,
        0,
        0,
        null,
        row);
  }

  public StatusCode decodeResponse(
      ByteBuffer source,
      ProtocolFrame frame,
      ProtocolResponse result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode decodeStatus = decode(source, frame);
    if (!decodeStatus.isOk()) {
      return decodeStatus;
    }
    if (!frame.isResponse() || frame.payloadBytes() < RESPONSE_FIXED_BYTES) {
      frame.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int offset = frame.payloadOffset();
    ByteBuffer bytes = frame.source();
    StatusCode status = statusFromStableCode(bytes.getInt(offset));
    int flags = bytes.getInt(offset + 4);
    int rows = bytes.getInt(offset + 8);
    int columns = bytes.getInt(offset + 12);
    long commitSequence = bytes.getLong(offset + 16);
    long key = bytes.getLong(offset + 24);
    long returned = bytes.getLong(offset + 32);
    long challengeHigh = bytes.getLong(offset + 40);
    long challengeLow = bytes.getLong(offset + 48);
    long nullMask = bytes.getLong(offset + 56);
    long varcharMask = bytes.getLong(offset + 64);
    boolean metadata = (flags & FLAG_COLUMN_METADATA) != 0;
    boolean rowAvailable = (flags & FLAG_ROW_AVAILABLE) != 0;
    if (status == null
        || (flags & ~(FLAG_ROW_AVAILABLE
            | FLAG_TRANSACTION_ACTIVE
            | FLAG_QUERY_ACTIVE
            | FLAG_COLUMN_METADATA)) != 0
        || rows < 0
        || columns < 0
        || columns > CommandResult.MAXIMUM_COLUMNS
        || commitSequence < 0
        || returned < 0
        || (nullMask & ~((1L << columns) - 1)) != 0
        || (varcharMask & ~((1L << columns) - 1)) != 0
        || metadata && (frame.type() != ProtocolMessageType.BEGIN_QUERY
            || !status.isOk()
            || flags != (FLAG_QUERY_ACTIVE | FLAG_COLUMN_METADATA)
            || columns <= 0
            || nullMask != 0)
        || !metadata && (rowAvailable != (columns > 0)
            || frame.payloadBytes() != RESPONSE_FIXED_BYTES + columns * Long.BYTES)) {
      frame.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.complete(
        status,
        flags,
        rows,
        columns,
        commitSequence,
        key,
        returned,
        challengeHigh,
        challengeLow,
        nullMask,
        varcharMask);
    if (metadata) {
      int metadataOffset = offset + RESPONSE_FIXED_BYTES;
      int end = offset + frame.payloadBytes();
      for (int index = 0; index < columns; index++) {
        if (metadataOffset >= end) {
          frame.reset();
          result.reset();
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        int length = bytes.get(metadataOffset++) & 0xff;
        if (length <= 0
            || length > MAXIMUM_COLUMN_NAME_BYTES
            || metadataOffset + length > end
            || !validColumnName(bytes, metadataOffset, length)) {
          frame.reset();
          result.reset();
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        result.columnNameAt(index, bytes, metadataOffset, length);
        metadataOffset += length;
      }
      if (metadataOffset != end) {
        frame.reset();
        result.reset();
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    } else {
      for (int index = 0; index < columns; index++) {
        result.valueAt(
            index,
            bytes.getLong(offset + RESPONSE_FIXED_BYTES + index * Long.BYTES));
      }
    }
    return StatusCode.OK;
  }

  private StatusCode encodeResponse(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      StatusCode status,
      int flags,
      int rows,
      int columns,
      long commitSequence,
      long key,
      long returned,
      long challengeHigh,
      long challengeLow,
      CommandResult command,
      RowResult row) {
    if (status == null || columns < 0 || columns > CommandResult.MAXIMUM_COLUMNS) {
      return invalidTarget(target);
    }
    long nullMask = command != null
        ? command.nullMask() : row == null ? 0 : row.nullMask();
    long varcharMask = command != null
        ? command.varcharMask() : row == null ? 0 : row.varcharMask();
    if ((nullMask & ~((1L << columns) - 1)) != 0
        || (varcharMask & ~((1L << columns) - 1)) != 0) {
      return invalidTarget(target);
    }
    int payloadBytes = RESPONSE_FIXED_BYTES + columns * Long.BYTES;
    StatusCode encoded = beginFrame(target, type, requestId, payloadBytes, FRAME_RESPONSE);
    if (!encoded.isOk()) {
      return encoded;
    }
    writeResponseFixed(
        target,
        status,
        flags,
        rows,
        columns,
        commitSequence,
        key,
        returned,
        challengeHigh,
        challengeLow,
        nullMask,
        varcharMask);
    for (int index = 0; index < columns; index++) {
      long value = command != null
          ? command.valueAt(index) : row == null ? 0 : row.valueAt(index);
      target.putLong(HEADER_BYTES + RESPONSE_FIXED_BYTES + index * Long.BYTES, value);
    }
    target.position(0);
    target.limit(HEADER_BYTES + payloadBytes);
    return StatusCode.OK;
  }

  private static void writeResponseFixed(
      ByteBuffer target,
      StatusCode status,
      int flags,
      int rows,
      int columns,
      long commitSequence,
      long key,
      long returned,
      long challengeHigh,
      long challengeLow,
      long nullMask,
      long varcharMask) {
    target.putInt(HEADER_BYTES, status.stableCode());
    target.putInt(HEADER_BYTES + 4, flags);
    target.putInt(HEADER_BYTES + 8, rows);
    target.putInt(HEADER_BYTES + 12, columns);
    target.putLong(HEADER_BYTES + 16, commitSequence);
    target.putLong(HEADER_BYTES + 24, key);
    target.putLong(HEADER_BYTES + 32, returned);
    target.putLong(HEADER_BYTES + 40, challengeHigh);
    target.putLong(HEADER_BYTES + 48, challengeLow);
    target.putLong(HEADER_BYTES + 56, nullMask);
    target.putLong(HEADER_BYTES + 64, varcharMask);
  }

  private static boolean validColumnName(CharSequence name) {
    if (name == null
        || name.length() <= 0
        || name.length() > MAXIMUM_COLUMN_NAME_BYTES
        || !identifierStart(name.charAt(0))) {
      return false;
    }
    for (int index = 1; index < name.length(); index++) {
      if (!identifierPart(name.charAt(index))) {
        return false;
      }
    }
    return true;
  }

  private static boolean validColumnName(ByteBuffer source, int offset, int length) {
    if (!identifierStart((char) (source.get(offset) & 0xff))) {
      return false;
    }
    for (int index = 1; index < length; index++) {
      if (!identifierPart((char) (source.get(offset + index) & 0xff))) {
        return false;
      }
    }
    return true;
  }

  private static boolean identifierStart(char value) {
    return value >= 'a' && value <= 'z'
        || value >= 'A' && value <= 'Z'
        || value == '_';
  }

  private static boolean identifierPart(char value) {
    return identifierStart(value) || value >= '0' && value <= '9';
  }

  private StatusCode beginFrame(
      ByteBuffer target,
      ProtocolMessageType type,
      long requestId,
      int payloadBytes,
      int flags) {
    if (target == null
        || target.isReadOnly()
        || type == null
        || requestId <= 0
        || payloadBytes < 0) {
      return invalidTarget(target);
    }
    int required = HEADER_BYTES + payloadBytes;
    if (target.capacity() < required) {
      empty(target);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    target.clear();
    target.order(ByteOrder.BIG_ENDIAN);
    target.putInt(0, MAGIC);
    target.putInt(4, VERSION);
    target.putInt(8, type.wireCode());
    target.putInt(12, flags);
    target.putLong(16, requestId);
    target.putInt(24, payloadBytes);
    target.putInt(28, 0);
    target.position(0);
    target.limit(required);
    return StatusCode.OK;
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
        if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
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

  private static StatusCode invalidTarget(ByteBuffer target) {
    if (target != null) {
      empty(target);
    }
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static void empty(ByteBuffer target) {
    target.clear();
    target.limit(0);
  }

  private static StatusCode statusFromStableCode(int code) {
    return switch (code) {
      case 0 -> StatusCode.OK;
      case 1000 -> StatusCode.RETRY;
      case 1001 -> StatusCode.FENCED;
      case 1002 -> StatusCode.CLOSED;
      case 2000 -> StatusCode.CANCELLED;
      case 3000 -> StatusCode.INVALID_EXTERNAL_INPUT;
      case 3001 -> StatusCode.CARDINALITY_VIOLATION;
      case 3002 -> StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
      case 3003 -> StatusCode.CHECK_VIOLATION;
      case 4000 -> StatusCode.CONFLICT;
      case 4001 -> StatusCode.NOT_OWNER;
      case 5000 -> StatusCode.RESOURCE_EXHAUSTED;
      case 5001 -> StatusCode.QUERY_TOO_COMPLEX;
      case 6000 -> StatusCode.TIMEOUT;
      case 7000 -> StatusCode.IO_FAILURE;
      case 8000 -> StatusCode.CORRUPTION;
      case 9000 -> StatusCode.INVARIANT_BROKEN;
      default -> null;
    };
  }
}
