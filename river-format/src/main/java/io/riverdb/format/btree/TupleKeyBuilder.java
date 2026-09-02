package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleEncodingSize;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlApproximateNumeric;
import java.nio.ByteBuffer;

/** Reusable allocation-free writer for canonical generic tuples and physical index keys. */
public final class TupleKeyBuilder {
  private ByteBuffer target;
  private int start;
  private int cursor;
  private int arity;
  private int count;
  private int maximumBytes;
  private boolean indexTuple;
  private boolean active;

  /** Starts an index-bounded tuple; finishPhysical adds its logical-row-ID suffix. */
  public StatusCode begin(ByteBuffer destination, int offset, int keyArity) {
    return beginIndex(destination, offset, keyArity);
  }

  public StatusCode beginIndex(ByteBuffer destination, int offset, int keyArity) {
    return begin(destination, offset, keyArity,
        TupleKeyCodec.MAX_INDEX_KEY_PARTS,
        TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES,
        true);
  }

  public StatusCode beginTuple(ByteBuffer destination, int offset, int tupleArity) {
    return begin(destination, offset, tupleArity,
        TupleKeyCodec.MAX_GENERIC_TUPLE_PARTS,
        TupleKeyCodec.MAX_GENERIC_TUPLE_BYTES,
        false);
  }

  public StatusCode addNull(int descriptor) {
    if (!canAdd(descriptor)) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!hasCapacity(2)) return StatusCode.RESOURCE_EXHAUSTED;
    target.put(cursor++, (byte) SqlTypeDescriptor.typeId(descriptor));
    target.put(cursor++, (byte) TupleKeyCodec.NULL_VALUE);
    count++;
    return StatusCode.OK;
  }

  public StatusCode addFixed(int descriptor, long value) {
    if (!canAdd(descriptor)
        || SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        || !SqlValueDomain.validFixed(descriptor, value)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!hasCapacity(TupleEncodingSize.maximumPartBytes(descriptor))) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    target.put(cursor++, (byte) SqlTypeDescriptor.typeId(descriptor));
    target.put(cursor++, (byte) TupleKeyCodec.PRESENT_VALUE);
    long sortable = SqlNumericTypeRules.isApproximate(descriptor)
        ? SqlApproximateNumeric.sortableBits(descriptor, value)
        : value ^ Long.MIN_VALUE;
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL) {
      TupleKeyCodec.putBigEndianLong(
          target, cursor, (value >> (Long.SIZE - 1)) ^ Long.MIN_VALUE);
      cursor += Long.BYTES;
      TupleKeyCodec.putBigEndianLong(target, cursor, value);
      cursor += Long.BYTES;
    } else {
      TupleKeyCodec.putBigEndianLong(target, cursor, sortable);
      cursor += Long.BYTES;
    }
    count++;
    return StatusCode.OK;
  }

  /** Adds one ordered signed 128-bit DECIMAL part without allocating. */
  public StatusCode addDecimal128(
      int descriptor, long high, long low) {
    if (!canAdd(descriptor)
        || SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_DECIMAL
        || !ExactDecimal128.fits(
            high, low, SqlTypeDescriptor.parameterOne(descriptor))) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!hasCapacity(TupleEncodingSize.maximumPartBytes(descriptor))) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    target.put(cursor++, (byte) SqlTypeDescriptor.TYPE_ID_DECIMAL);
    target.put(cursor++, (byte) TupleKeyCodec.PRESENT_VALUE);
    TupleKeyCodec.putBigEndianLong(target, cursor, high ^ Long.MIN_VALUE);
    cursor += Long.BYTES;
    TupleKeyCodec.putBigEndianLong(target, cursor, low);
    cursor += Long.BYTES;
    count++;
    return StatusCode.OK;
  }

  public StatusCode addText(int descriptor, CharSequence value) {
    if (!canAdd(descriptor)
        || SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR
        || value == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    int scalars = countScalars(value, SqlTypeDescriptor.parameterOne(descriptor));
    if (scalars < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int bytes = 2 + (scalars + 1) * Integer.BYTES;
    if (!hasCapacity(bytes)) return StatusCode.RESOURCE_EXHAUSTED;
    target.put(cursor++, (byte) SqlTypeDescriptor.TYPE_ID_VARCHAR);
    target.put(cursor++, (byte) TupleKeyCodec.PRESENT_VALUE);
    for (int index = 0; index < value.length(); index++) {
      char first = value.charAt(index);
      int scalar = Character.isHighSurrogate(first)
          ? Character.toCodePoint(first, value.charAt(++index)) : first;
      TupleKeyCodec.putBigEndianInt(target, cursor, scalar + 1);
      cursor += Integer.BYTES;
    }
    TupleKeyCodec.putBigEndianInt(target, cursor, 0);
    cursor += Integer.BYTES;
    count++;
    return StatusCode.OK;
  }

  /** Completes a generic tuple with no storage identity suffix. */
  public StatusCode finishTuple() {
    if (!ready()) return StatusCode.INVALID_EXTERNAL_INPUT;
    target.put(start + 1, (byte) 0);
    active = false;
    return StatusCode.OK;
  }

  /** Completes an index key as userTuple || logicalRowId. */
  public StatusCode finishPhysical(long logicalRowId) {
    if (!ready() || !indexTuple || logicalRowId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int userBytes = cursor - start;
    if (userBytes > TupleKeyCodec.MAX_INDEX_USER_KEY_BYTES
        || !hasCapacity(TupleEncodingSize.PHYSICAL_SUFFIX_BYTES)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    target.put(start + 1, (byte) TupleKeyCodec.FLAG_PHYSICAL);
    TupleKeyCodec.putBigEndianLong(target, cursor, logicalRowId);
    cursor += TupleKeyCodec.LOGICAL_ROW_ID_BYTES;
    active = false;
    return StatusCode.OK;
  }

  public StatusCode finish(long logicalRowId) {
    return finishPhysical(logicalRowId);
  }

  public int keyOffset() {
    return start;
  }

  public int keyBytes() {
    return active || target == null ? 0 : cursor - start;
  }

  public void reset() {
    target = null;
    start = 0;
    cursor = 0;
    arity = 0;
    count = 0;
    maximumBytes = 0;
    indexTuple = false;
    active = false;
  }

  private StatusCode begin(
      ByteBuffer destination, int offset, int tupleArity,
      int maximumArity, int byteLimit, boolean forIndex) {
    reset();
    int headerBytes = TupleKeyCodec.headerBytes(tupleArity);
    if (destination == null || destination.isReadOnly() || offset < 0
        || tupleArity <= 0 || tupleArity > maximumArity
        || headerBytes == 0 || destination.limit() - offset < headerBytes + 2) {
      return tupleArity > maximumArity
          ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    target = destination;
    start = offset;
    arity = tupleArity;
    maximumBytes = byteLimit;
    indexTuple = forIndex;
    destination.put(offset, (byte) TupleKeyCodec.VERSION);
    destination.put(offset + 1, (byte) 0);
    cursor = TupleKeyCodec.putUnsignedVarInt(destination, offset + 2, tupleArity);
    active = true;
    return StatusCode.OK;
  }

  private boolean ready() {
    return active && count == arity;
  }

  private boolean canAdd(int descriptor) {
    return active && count < arity && SqlTypeDescriptor.isValid(descriptor);
  }

  private boolean hasCapacity(int bytes) {
    return bytes >= 0 && cursor - start <= maximumBytes - bytes
        && target.limit() - cursor >= bytes;
  }

  private static int countScalars(CharSequence value, int maximum) {
    int scalars = 0;
    for (int index = 0; index < value.length(); index++) {
      char first = value.charAt(index);
      if (Character.isHighSurrogate(first)) {
        if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return -1;
      } else if (Character.isLowSurrogate(first)) {
        return -1;
      }
      if (++scalars > maximum) return -1;
    }
    return scalars;
  }
}
