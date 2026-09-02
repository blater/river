package io.riverdb.format.btree;

import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.tuple.TupleEncodingSize;
import io.riverdb.base.tuple.TupleShape;
import java.nio.ByteBuffer;

/** Canonical order-preserving generic tuples and physical index keys. */
public final class TupleKeyCodec {
  public static final int VERSION = 3;
  public static final int FLAG_PHYSICAL = 1;
  public static final int MINIMUM_HEADER_BYTES = 3;
  public static final int LOGICAL_ROW_ID_BYTES = TupleEncodingSize.PHYSICAL_SUFFIX_BYTES;
  public static final int MAX_GENERIC_TUPLE_PARTS = SqlShapeLimits.MAX_TUPLE_PARTS;
  public static final int MAX_INDEX_KEY_PARTS = SqlShapeLimits.MAX_KEY_PARTS;
  public static final int MAX_GENERIC_TUPLE_BYTES = SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES;
  public static final int MAX_INDEX_USER_KEY_BYTES = SqlShapeLimits.MAX_INDEX_USER_KEY_BYTES;
  public static final int MAX_PHYSICAL_INDEX_KEY_BYTES =
      SqlShapeLimits.MAX_PHYSICAL_INDEX_KEY_BYTES;
  static final int NULL_VALUE = 0;
  static final int PRESENT_VALUE = 1;

  private TupleKeyCodec() { }

  public static boolean validate(ByteBuffer source, int offset, int length) {
    return TupleKeyStructureValidation.validate(source, offset, length);
  }

  public static boolean matchesShape(
      ByteBuffer source, int offset, int length, TupleShape shape) {
    return TupleKeyShapeValidation.matches(source, offset, length, shape);
  }

  public static boolean matchesPhysicalIndexKey(
      ByteBuffer source, int offset, int length, TupleShape shape) {
    return length <= MAX_PHYSICAL_INDEX_KEY_BYTES
        && isPhysical(source, offset, length)
        && shape != null
        && shape.partCount() <= MAX_INDEX_KEY_PARTS
        && matchesShape(source, offset, length, shape);
  }

  public static int arity(ByteBuffer source, int offset, int length) {
    if (source == null || offset < 0 || length < MINIMUM_HEADER_BYTES
        || source.limit() - offset < length
        || Byte.toUnsignedInt(source.get(offset)) != VERSION) return 0;
    int value = 0;
    int shift = 0;
    int cursor = offset + 2;
    for (int count = 0; count < 3 && cursor < offset + length; count++) {
      int next = Byte.toUnsignedInt(source.get(cursor++));
      value |= (next & 0x7f) << shift;
      if ((next & 0x80) == 0) {
        return value > 0 && value <= MAX_GENERIC_TUPLE_PARTS ? value : 0;
      }
      shift += 7;
    }
    return 0;
  }

  public static int headerBytes(ByteBuffer source, int offset, int length) {
    int tupleArity = arity(source, offset, length);
    return TupleEncodingSize.headerBytes(tupleArity);
  }

  public static int headerBytes(int tupleArity) {
    return TupleEncodingSize.headerBytes(tupleArity);
  }

  public static boolean isPhysical(ByteBuffer source, int offset, int length) {
    return source != null && offset >= 0 && length >= MINIMUM_HEADER_BYTES
        && source.limit() - offset >= length
        && (Byte.toUnsignedInt(source.get(offset + 1)) & FLAG_PHYSICAL) != 0;
  }

  public static long logicalRowId(ByteBuffer source, int offset, int length) {
    return validate(source, offset, length) && isPhysical(source, offset, length)
        ? getBigEndianLong(source, offset + length - LOGICAL_ROW_ID_BYTES) : 0;
  }

  public static boolean containsNull(ByteBuffer source, int offset, int length) {
    if (!validate(source, offset, length)) return false;
    int cursor = offset + headerBytes(source, offset, length);
    int parts = arity(source, offset, length);
    int valuesEnd = offset + userTupleBytes(source, offset, length);
    for (int part = 0; part < parts; part++) {
      int type = Byte.toUnsignedInt(source.get(cursor++));
      int marker = Byte.toUnsignedInt(source.get(cursor++));
      if (marker == NULL_VALUE) return true;
      cursor = TupleKeyStructureValidation.valueEnd(source, cursor, valuesEnd, type);
    }
    return false;
  }

  public static int compare(
      ByteBuffer left, int leftOffset, int leftLength,
      ByteBuffer right, int rightOffset, int rightLength) {
    int shared = Math.min(leftLength, rightLength);
    for (int index = 0; index < shared; index++) {
      int comparison = Integer.compare(
          Byte.toUnsignedInt(left.get(leftOffset + index)),
          Byte.toUnsignedInt(right.get(rightOffset + index)));
      if (comparison != 0) return comparison;
    }
    return Integer.compare(leftLength, rightLength);
  }

  public static int compareUserTuple(
      ByteBuffer left, int leftOffset, int leftLength,
      ByteBuffer right, int rightOffset, int rightLength) {
    return compare(
        left, leftOffset, userTupleBytes(left, leftOffset, leftLength),
        right, rightOffset, userTupleBytes(right, rightOffset, rightLength));
  }

  /** Compares the first {@code prefixParts}; inputs must already match compatible shapes. */
  public static int comparePrefix(
      ByteBuffer left, int leftOffset, int leftLength,
      ByteBuffer right, int rightOffset, int rightLength,
      int prefixParts) {
    int leftArity = arity(left, leftOffset, leftLength);
    int rightArity = arity(right, rightOffset, rightLength);
    if (prefixParts <= 0 || prefixParts > leftArity || prefixParts > rightArity) return 0;
    int leftCursor = leftOffset + headerBytes(left, leftOffset, leftLength);
    int rightCursor = rightOffset + headerBytes(right, rightOffset, rightLength);
    int leftEnd = leftOffset + userTupleBytes(left, leftOffset, leftLength);
    int rightEnd = rightOffset + userTupleBytes(right, rightOffset, rightLength);
    for (int part = 0; part < prefixParts; part++) {
      int leftPartEnd = TupleKeyStructureValidation.partEnd(left, leftCursor, leftEnd);
      int rightPartEnd = TupleKeyStructureValidation.partEnd(right, rightCursor, rightEnd);
      int comparison = compare(
          left, leftCursor, leftPartEnd - leftCursor,
          right, rightCursor, rightPartEnd - rightCursor);
      if (comparison != 0) return comparison;
      leftCursor = leftPartEnd;
      rightCursor = rightPartEnd;
    }
    return 0;
  }

  static int userTupleBytes(ByteBuffer source, int offset, int length) {
    return isPhysical(source, offset, length) ? length - LOGICAL_ROW_ID_BYTES : length;
  }

  static int putUnsignedVarInt(ByteBuffer target, int offset, int value) {
    int cursor = offset;
    while ((value & ~0x7f) != 0) {
      target.put(cursor++, (byte) (value & 0x7f | 0x80));
      value >>>= 7;
    }
    target.put(cursor++, (byte) value);
    return cursor;
  }

  static void putBigEndianInt(ByteBuffer target, int offset, int value) {
    target.put(offset, (byte) (value >>> 24));
    target.put(offset + 1, (byte) (value >>> 16));
    target.put(offset + 2, (byte) (value >>> 8));
    target.put(offset + 3, (byte) value);
  }

  static void putBigEndianLong(ByteBuffer target, int offset, long value) {
    putBigEndianInt(target, offset, (int) (value >>> 32));
    putBigEndianInt(target, offset + Integer.BYTES, (int) value);
  }

  static long getBigEndianLong(ByteBuffer source, int offset) {
    return (long) getBigEndianInt(source, offset) << 32
        | Integer.toUnsignedLong(getBigEndianInt(source, offset + Integer.BYTES));
  }

  static int getBigEndianInt(ByteBuffer source, int offset) {
    return Byte.toUnsignedInt(source.get(offset)) << 24
        | Byte.toUnsignedInt(source.get(offset + 1)) << 16
        | Byte.toUnsignedInt(source.get(offset + 2)) << 8
        | Byte.toUnsignedInt(source.get(offset + 3));
  }

}
