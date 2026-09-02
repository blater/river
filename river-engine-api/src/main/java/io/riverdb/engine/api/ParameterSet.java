package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Caller-owned bounded typed values borrowed for one synchronous SQL admission. */
public final class ParameterSet {
  public static final int MAXIMUM_PARAMETERS = SqlShapeLimits.MAX_PARAMETERS;
  public static final int MAXIMUM_TEXT_BYTES = SqlShapeLimits.MAX_ENCODED_PARAMETER_BYTES;
  private final ParameterValues values;

  public ParameterSet(int parameterCapacity, int textCapacity) {
    this(parameterCapacity, textCapacity, RetainedMemoryLease.unbounded());
  }

  public ParameterSet(
      int parameterCapacity, int textCapacity, RetainedMemoryLease retainedMemory) {
    values = new ParameterValues(parameterCapacity, textCapacity, retainedMemory);
  }

  public void reset() { values.reset(); }
  public StatusCode appendNull(int descriptor) { return values.appendNull(descriptor); }
  public StatusCode appendFixed(int descriptor, long value) {
    return values.appendFixed(descriptor, value);
  }
  public StatusCode appendSmallint(short value) {
    return values.appendFixed(SqlTypeDescriptor.SMALLINT, value);
  }
  public StatusCode appendInteger(int value) {
    return values.appendFixed(SqlTypeDescriptor.INTEGER, value);
  }
  public StatusCode appendBigint(long value) {
    return values.appendFixed(SqlTypeDescriptor.BIGINT, value);
  }
  public StatusCode appendDecimal(int precision, int scale, long unscaled) {
    int descriptor = SqlTypeDescriptor.decimal(precision, scale);
    return descriptor == 0
        ? StatusCode.INVALID_EXTERNAL_INPUT : values.appendFixed(descriptor, unscaled);
  }
  public StatusCode appendDecimal128(
      int precision, int scale, long unscaledHigh, long unscaledLow) {
    int descriptor = SqlTypeDescriptor.decimal(precision, scale);
    return descriptor == 0 ? StatusCode.INVALID_EXTERNAL_INPUT
        : values.appendDecimal128(descriptor, unscaledHigh, unscaledLow);
  }
  public StatusCode appendReal(float value) {
    return values.appendFixed(SqlTypeDescriptor.REAL, SqlApproximateNumeric.realBits(value));
  }
  public StatusCode appendDouble(double value) {
    return values.appendFixed(SqlTypeDescriptor.DOUBLE, SqlApproximateNumeric.doubleBits(value));
  }
  public StatusCode appendText(int descriptor, CharSequence value) {
    return values.appendText(descriptor, value);
  }
  public StatusCode appendUtf8(int descriptor, ByteBuffer source, int offset, int length) {
    return values.appendUtf8(descriptor, source, offset, length);
  }
  public StatusCode reserve(int parameters, int textBytes) {
    return values.reserve(parameters, textBytes);
  }
  public int count() { return values.count(); }
  public boolean isNull(int index) { return values.isNull(index); }
  public int typeDescriptorAt(int index) { return values.descriptor(index); }
  public long valueAt(int index) { return values.value(index); }
  public short smallintAt(int index) {
    return PublicNumericValue.smallint(values.descriptor(index), values.value(index));
  }
  public int integerAt(int index) {
    return PublicNumericValue.integer(values.descriptor(index), values.value(index));
  }
  public long bigintAt(int index) {
    return PublicNumericValue.bigint(values.descriptor(index), values.value(index));
  }
  public long decimalUnscaledAt(int index) {
    return PublicNumericValue.decimal(values.descriptor(index), values.value(index));
  }
  public long decimalUnscaledHighAt(int index) {
    return PublicNumericValue.decimalHigh(
        values.descriptor(index), values.decimalHigh(index), values.value(index));
  }
  public long decimalUnscaledLowAt(int index) {
    return PublicNumericValue.decimalLow(values.descriptor(index), values.value(index));
  }
  public float realAt(int index) {
    return PublicNumericValue.real(values.descriptor(index), values.value(index));
  }
  public double doubleAt(int index) {
    return PublicNumericValue.doubleValue(values.descriptor(index), values.value(index));
  }
  public int textLengthAt(int index) { return values.textLength(index); }
  public byte textByteAt(int index, int byteIndex) { return values.textByte(index, byteIndex); }
  public int copyTextAt(int index, char[] target, int offset) {
    return values.copyText(index, target, offset);
  }
  public int textBytes() { return values.textBytes(); }

  /** Scrubs values and sheds retained capacity above the small prepared-request warm floor. */
  public StatusCode releaseHighWater() { return values.releaseHighWater(); }
  public StatusCode release() { return values.release(); }

  public long retainedBytes() { return values.retainedBytes(); }
  public static long maximumRetainedBytes() { return ParameterValues.maximumRetainedBytes(); }
  public static long retainedFloorBytes() { return ParameterValues.retainedFloorBytes(); }
}
