package io.riverdb.format.btree;

import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlApproximateNumeric;

/** Reusable parser state for one borrowed TupleInput. */
final class TupleInputCursor {
  private TupleInput input;
  private int cursor;
  private int valuesEnd;
  private int arity;
  private int part;
  private int type;
  private int marker;
  private int valueStart;
  private int valueEnd;
  private boolean physical;

  boolean open(TupleInput source, TupleShape shape) {
    clear();
    if (source == null || shape == null || source.byteLength() < 5
        || source.byteAt(0) != TupleKeyCodec.VERSION) return false;
    int flags = source.byteAt(1);
    physical = flags == TupleKeyCodec.FLAG_PHYSICAL;
    if (flags != 0 && !physical) return false;
    int shift = 0;
    cursor = 2;
    while (cursor < source.byteLength() && shift <= 14) {
      int next = source.byteAt(cursor++);
      arity |= (next & 0x7f) << shift;
      if ((next & 0x80) == 0) break;
      shift += 7;
    }
    if (arity != shape.partCount()
        || cursor != 2 + (arity < 1 << 7 ? 1 : arity < 1 << 14 ? 2 : 3)) return false;
    valuesEnd = source.byteLength() - (physical ? Long.BYTES : 0);
    input = source;
    return valuesEnd >= cursor + arity * 2;
  }

  boolean next(int descriptor) {
    if (input == null || part >= arity || valuesEnd - cursor < 2) return false;
    type = input.byteAt(cursor++);
    marker = input.byteAt(cursor++);
    if (type != SqlTypeDescriptor.typeId(descriptor)
        || marker != TupleKeyCodec.NULL_VALUE && marker != TupleKeyCodec.PRESENT_VALUE) return false;
    valueStart = cursor;
    if (marker == TupleKeyCodec.NULL_VALUE) {
      valueEnd = cursor;
    } else if (type == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      if (!scanText(SqlTypeDescriptor.parameterOne(descriptor))) return false;
    } else if (type == SqlTypeDescriptor.TYPE_ID_DECIMAL) {
      if (valuesEnd - cursor < Long.BYTES * 2) return false;
      valueEnd = cursor += Long.BYTES * 2;
      long high = signedLong(valueStart) ^ Long.MIN_VALUE;
      long low = signedLong(valueStart + Long.BYTES);
      if (!ExactDecimal128.fits(
          high, low, SqlTypeDescriptor.parameterOne(descriptor))) return false;
    } else {
      if (valuesEnd - cursor < Long.BYTES) return false;
      valueEnd = cursor += Long.BYTES;
      long encoded = signedLong(valueStart);
      long value = SqlNumericTypeRules.isApproximate(descriptor)
          ? SqlApproximateNumeric.valueBits(descriptor, encoded)
          : encoded ^ Long.MIN_VALUE;
      if (!SqlValueDomain.validFixed(descriptor, value)) {
        return false;
      }
    }
    part++;
    return true;
  }

  boolean complete() {
    if (input == null || part != arity || cursor != valuesEnd) return false;
    return !physical || signedLong(valuesEnd) > 0;
  }

  int marker() { return marker; }
  int valueStart() { return valueStart; }
  int valueEnd() { return valueEnd; }
  boolean physical() { return physical; }
  int byteAt(int index) { return input.byteAt(index); }
  long logicalRowId() { return physical ? signedLong(valuesEnd) : 0; }

  private boolean scanText(int maximumScalars) {
    int scalars = 0;
    while (valuesEnd - cursor >= Integer.BYTES) {
      long encoded = unsignedInt(cursor);
      cursor += Integer.BYTES;
      if (encoded == 0) {
        valueEnd = cursor;
        return true;
      }
      int scalar = (int) encoded - 1;
      if (!Character.isValidCodePoint(scalar)
          || scalar >= Character.MIN_SURROGATE && scalar <= Character.MAX_SURROGATE
          || ++scalars > maximumScalars) return false;
    }
    return false;
  }

  private long signedLong(int offset) {
    return unsignedInt(offset) << Integer.SIZE | unsignedInt(offset + Integer.BYTES);
  }

  private long unsignedInt(int offset) {
    return (long) input.byteAt(offset) << 24
        | (long) input.byteAt(offset + 1) << 16
        | (long) input.byteAt(offset + 2) << 8
        | input.byteAt(offset + 3);
  }

  private void clear() {
    input = null;
    cursor = 0;
    valuesEnd = 0;
    arity = 0;
    part = 0;
    type = 0;
    marker = 0;
    valueStart = 0;
    valueEnd = 0;
    physical = false;
  }
}
