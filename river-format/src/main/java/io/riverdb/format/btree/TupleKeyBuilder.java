package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import java.nio.ByteBuffer;

/** Reusable allocation-free writer for one canonical tuple key. */
public final class TupleKeyBuilder {
  private ByteBuffer target;
  private int start;
  private int cursor;
  private int arity;
  private int count;
  private boolean active;

  public StatusCode begin(ByteBuffer destination, int offset, int keyArity) {
    reset();
    if (destination == null
        || destination.isReadOnly()
        || offset < 0
        || destination.limit() - offset < TupleKeyCodec.HEADER_BYTES
            + 2 + TupleKeyCodec.LOGICAL_ROW_ID_BYTES
        || keyArity <= 0
        || keyArity > TupleKeyCodec.MAXIMUM_ARITY) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    target = destination;
    start = offset;
    cursor = offset + TupleKeyCodec.HEADER_BYTES;
    arity = keyArity;
    destination.put(offset, (byte) TupleKeyCodec.VERSION);
    destination.put(offset + 1, (byte) keyArity);
    destination.put(offset + 2, (byte) 0);
    destination.put(offset + 3, (byte) 0);
    active = true;
    return StatusCode.OK;
  }

  public StatusCode addNull(int descriptor) {
    if (!canAdd(descriptor) || !hasCapacity(2)) return statusForAdd(descriptor, 2);
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
    if (!hasCapacity(2 + Long.BYTES)) return StatusCode.RESOURCE_EXHAUSTED;
    target.put(cursor++, (byte) SqlTypeDescriptor.typeId(descriptor));
    target.put(cursor++, (byte) TupleKeyCodec.PRESENT_VALUE);
    TupleKeyCodec.putBigEndianLong(target, cursor, value ^ Long.MIN_VALUE);
    cursor += Long.BYTES;
    count++;
    return StatusCode.OK;
  }

  public StatusCode addText(int descriptor, CharSequence value) {
    if (!canAdd(descriptor)
        || SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR
        || value == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int scalars = 0;
    for (int index = 0; index < value.length(); index++) {
      char first = value.charAt(index);
      if (Character.isHighSurrogate(first)) {
        if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
      } else if (Character.isLowSurrogate(first)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (++scalars > SqlTypeDescriptor.parameterOne(descriptor)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
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

  public StatusCode finish(long logicalRowId) {
    if (!active || count != arity || logicalRowId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!hasCapacity(TupleKeyCodec.LOGICAL_ROW_ID_BYTES)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    TupleKeyCodec.putBigEndianLong(target, cursor, logicalRowId);
    cursor += TupleKeyCodec.LOGICAL_ROW_ID_BYTES;
    active = false;
    return StatusCode.OK;
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
    active = false;
  }

  private boolean canAdd(int descriptor) {
    return active
        && count < arity
        && SqlTypeDescriptor.isValid(descriptor);
  }

  private boolean hasCapacity(int bytes) {
    return cursor - start <= TupleKeyCodec.MAXIMUM_KEY_BYTES - bytes
        && target.limit() - cursor >= bytes;
  }

  private StatusCode statusForAdd(int descriptor, int bytes) {
    return !canAdd(descriptor) ? StatusCode.INVALID_EXTERNAL_INPUT
        : !hasCapacity(bytes) ? StatusCode.RESOURCE_EXHAUSTED
        : StatusCode.OK;
  }
}
