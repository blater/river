package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.RetainedMemoryLease;
import java.nio.ByteBuffer;

/** Session-owned strict decoder for one v4 SQL request payload. */
public final class ProtocolSqlRequestDecoder {
  private static final int REQUEST_HEADER_BYTES = Integer.BYTES + Short.BYTES * 2;
  private final ProtocolUtf8Decoder text;
  private final ProtocolParameterDecoder parameterDecoder;
  private String sql;

  public ProtocolSqlRequestDecoder() {
    this(RetainedMemoryLease.unbounded(), RetainedMemoryLease.unbounded());
  }

  public ProtocolSqlRequestDecoder(
      RetainedMemoryLease textMemory, RetainedMemoryLease parameterMemory) {
    this(new ProtocolUtf8Decoder(
        io.riverdb.base.sql.SqlShapeLimits.MAX_SQL_TEXT_BYTES,
        String::new,
        textMemory), parameterMemory);
  }

  ProtocolSqlRequestDecoder(ProtocolUtf8Decoder textDecoder) {
    this(textDecoder, RetainedMemoryLease.unbounded());
  }

  private ProtocolSqlRequestDecoder(
      ProtocolUtf8Decoder textDecoder, RetainedMemoryLease parameterMemory) {
    text = textDecoder;
    parameterDecoder = new ProtocolParameterDecoder(parameterMemory);
  }

  public StatusCode decode(ProtocolFrame frame) {
    reset();
    if (frame == null || frame.isResponse() || !sqlType(frame.type())
        || frame.payloadBytes() < REQUEST_HEADER_BYTES) {
      return invalid(frame);
    }
    ByteBuffer source = frame.source();
    int input = frame.payloadOffset();
    int end = input + frame.payloadBytes();
    int sqlBytes = source.getInt(input);
    int parameterCount = Short.toUnsignedInt(source.getShort(input + Integer.BYTES));
    int reserved = Short.toUnsignedInt(
        source.getShort(input + Integer.BYTES + Short.BYTES));
    input += REQUEST_HEADER_BYTES;
    if (sqlBytes <= 0 || reserved != 0
        || frame.type() == ProtocolMessageType.PREPARE && parameterCount != 0
        || parameterCount > ParameterSet.MAXIMUM_PARAMETERS
        || input > end - sqlBytes) {
      return invalid(frame);
    }
    StatusCode status = text.decode(frame, input - frame.payloadOffset(), sqlBytes);
    if (!status.isOk()) {
      return status == StatusCode.RESOURCE_EXHAUSTED
          ? failure(frame, status) : invalid(frame);
    }
    sql = text.text();
    input += sqlBytes;
    status = parameterDecoder.decode(source, input, end, parameterCount);
    if (!status.isOk()) return failure(frame, status);
    StatusCode erased = erase(frame);
    if (!erased.isOk()) {
      reset();
    }
    return erased;
  }

  public String sql() {
    return sql;
  }

  public ParameterSet parameters() {
    return parameterDecoder.parameters();
  }

  /** Releases decoded user values after synchronous engine admission. */
  public void reset() {
    sql = null;
    text.reset();
    parameterDecoder.reset();
  }

  public StatusCode releaseHighWater() {
    sql = null;
    StatusCode textStatus = text.releaseHighWater();
    StatusCode parameterStatus = parameterDecoder.releaseHighWater();
    return textStatus.isOk() ? parameterStatus : textStatus;
  }

  public StatusCode release() {
    sql = null;
    StatusCode textStatus = text.release();
    StatusCode parameterStatus = parameterDecoder.release();
    return textStatus.isOk() ? parameterStatus : textStatus;
  }

  private StatusCode invalid(ProtocolFrame frame) {
    return failure(frame, StatusCode.INVALID_EXTERNAL_INPUT);
  }

  private StatusCode failure(ProtocolFrame frame, StatusCode status) {
    erase(frame);
    reset();
    return status;
  }

  private static StatusCode erase(ProtocolFrame frame) {
    return frame == null
        ? StatusCode.INVALID_EXTERNAL_INPUT : frame.erasePayload();
  }

  private static boolean sqlType(ProtocolMessageType type) {
    return type == ProtocolMessageType.EXECUTE
        || type == ProtocolMessageType.BEGIN_QUERY
        || type == ProtocolMessageType.PREPARE;
  }
}
