package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.RetainedMemoryLease;
import io.riverdb.engine.api.TransactionProgramArguments;
import java.nio.ByteBuffer;

/** Reusable decoder for the typed parameter entries in one SQL request. */
final class ProtocolParameterDecoder {
  static final int HEADER_BYTES = Integer.BYTES + Byte.BYTES * 2 + Short.BYTES;
  private static final int NULL_FLAG = 1;
  private final ParameterSet parameters;
  private int next;
  private StatusCode status = StatusCode.OK;

  ProtocolParameterDecoder(RetainedMemoryLease memory) {
    parameters = new ParameterSet(
        ParameterSet.MAXIMUM_PARAMETERS, ParameterSet.MAXIMUM_TEXT_BYTES, memory);
  }

  StatusCode decode(ByteBuffer source, int offset, int end, int count) {
    parameters.reset();
    if (source == null || offset < 0 || end < offset || end > source.limit()
        || count < 0 || count > (end - offset) / HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int textBytes = validatedTextBytes(source, offset, end, count);
    if (textBytes < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    status = parameters.reserve(count, textBytes);
    if (!status.isOk()) return status;
    next = offset;
    for (int index = 0; index < count && status.isOk(); index++) decodeOne(source, end);
    if (status.isOk() && next != end) status = StatusCode.INVALID_EXTERNAL_INPUT;
    return status;
  }

  static StatusCode decodeProgram(
      ByteBuffer source, int offset, int end, int count,
      TransactionProgramArguments target, ProtocolProgramTextDecoder text) {
    if (target == null || text == null || source == null || offset < 0 || end < offset
        || end > source.limit() || count < 0
        || count > (end - offset) / HEADER_BYTES) return StatusCode.INVALID_EXTERNAL_INPUT;
    target.reset();
    int next = offset;
    for (int index = 0; index < count; index++) {
      if (next > end - HEADER_BYTES) return StatusCode.INVALID_EXTERNAL_INPUT;
      int descriptor = source.getInt(next);
      int flags = Byte.toUnsignedInt(source.get(next + Integer.BYTES));
      int reserved = Byte.toUnsignedInt(source.get(next + Integer.BYTES + Byte.BYTES));
      int bytes = Short.toUnsignedInt(
          source.getShort(next + Integer.BYTES + Byte.BYTES * 2));
      if ((flags & ~NULL_FLAG) != 0 || reserved != 0 || next + HEADER_BYTES > end - bytes
          || !validWidth(descriptor, flags == NULL_FLAG, bytes)
          || flags == NULL_FLAG && !SqlTypeDescriptor.isValid(descriptor)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int value = next + HEADER_BYTES;
      StatusCode status;
      if (flags == NULL_FLAG) {
        status = target.setNull(index, descriptor);
      } else if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        status = text.decode(source, value, bytes);
        if (status.isOk()) status = target.setText(index, descriptor, text.view());
      } else if (ProtocolDecimal128.isWide(descriptor)) {
        status = target.setDecimal128(
            index, descriptor, source.getLong(value), source.getLong(value + Long.BYTES));
      } else {
        status = target.setFixed(index, descriptor, source.getLong(value));
      }
      if (!status.isOk()) return status;
      next = value + bytes;
    }
    return next == end ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static int validatedTextBytes(ByteBuffer source, int offset, int end, int count) {
    int next = offset;
    int textBytes = 0;
    for (int index = 0; index < count; index++) {
      if (next > end - HEADER_BYTES) return -1;
      int descriptor = source.getInt(next);
      int flags = Byte.toUnsignedInt(source.get(next + Integer.BYTES));
      int reserved = Byte.toUnsignedInt(source.get(next + Integer.BYTES + Byte.BYTES));
      int bytes = Short.toUnsignedInt(
          source.getShort(next + Integer.BYTES + Byte.BYTES * 2));
      next += HEADER_BYTES;
      if ((flags & ~NULL_FLAG) != 0 || reserved != 0 || next > end - bytes
          || !validWidth(descriptor, flags == NULL_FLAG, bytes)) return -1;
      if (flags != NULL_FLAG
          && SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        if (textBytes > ParameterSet.MAXIMUM_TEXT_BYTES - bytes) return -1;
        textBytes += bytes;
      }
      next += bytes;
    }
    return next == end ? textBytes : -1;
  }

  private static boolean validWidth(int descriptor, boolean isNull, int bytes) {
    if (isNull) return bytes == 0
        && (descriptor == 0 || SqlTypeDescriptor.isValid(descriptor));
    if (!SqlTypeDescriptor.isValid(descriptor)) return false;
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) return true;
    return bytes == (ProtocolDecimal128.isWide(descriptor) ? Long.BYTES * 2 : Long.BYTES);
  }

  private void decodeOne(ByteBuffer source, int end) {
    if (next > end - HEADER_BYTES) { invalid(); return; }
    int descriptor = source.getInt(next);
    int flags = Byte.toUnsignedInt(source.get(next + Integer.BYTES));
    int reserved = Byte.toUnsignedInt(source.get(next + Integer.BYTES + Byte.BYTES));
    int bytes = Short.toUnsignedInt(source.getShort(next + Integer.BYTES + Byte.BYTES * 2));
    next += HEADER_BYTES;
    if ((flags & ~NULL_FLAG) != 0 || reserved != 0 || next > end - bytes) {
      invalid();
      return;
    }
    status = append(source, descriptor, flags == NULL_FLAG, bytes);
    if (!status.isOk() && status != StatusCode.RESOURCE_EXHAUSTED) invalid();
    if (status.isOk()) next += bytes;
  }

  private StatusCode append(ByteBuffer source, int descriptor, boolean isNull, int bytes) {
    if (isNull) return bytes == 0
        ? parameters.appendNull(descriptor) : StatusCode.INVALID_EXTERNAL_INPUT;
    if (!SqlTypeDescriptor.isValid(descriptor)) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return parameters.appendUtf8(descriptor, source, next, bytes);
    }
    if (ProtocolDecimal128.isWide(descriptor)) {
      return bytes == Long.BYTES * 2
          ? parameters.appendDecimal128(
              SqlTypeDescriptor.parameterOne(descriptor),
              SqlTypeDescriptor.parameterTwo(descriptor),
              source.getLong(next), source.getLong(next + Long.BYTES))
          : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return bytes == Long.BYTES ? parameters.appendFixed(
        descriptor, source.getLong(next)) : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  ParameterSet parameters() { return parameters; }
  void reset() { parameters.reset(); next = 0; status = StatusCode.OK; }
  StatusCode releaseHighWater() {
    next = 0;
    status = StatusCode.OK;
    return parameters.releaseHighWater();
  }
  StatusCode release() {
    next = 0;
    status = StatusCode.OK;
    return parameters.release();
  }
  private void invalid() { status = StatusCode.INVALID_EXTERNAL_INPUT; }
}
