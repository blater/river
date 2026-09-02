package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import java.nio.ByteBuffer;

/** Caller-owned tuple scan request; encoded bounds are borrowed only during cursor open. */
public final class TupleBTreeScanBounds {
  public static final int FORWARD = 1;
  public static final int REVERSE = -1;
  static final int ALL = 1;
  static final int EXACT = 2;
  static final int PREFIX = 3;
  static final int RANGE = 4;

  ByteBuffer lower;
  int lowerOffset;
  int lowerLength;
  TupleShape lowerShape;
  boolean lowerInclusive;
  ByteBuffer upper;
  int upperOffset;
  int upperLength;
  TupleShape upperShape;
  boolean upperInclusive;
  int direction;
  int kind;

  public StatusCode setAll(int scanDirection) {
    StatusCode status = setRange(
        null, 0, 0, null, true, null, 0, 0, null, true, scanDirection);
    if (status.isOk()) kind = ALL;
    return status;
  }

  public StatusCode setExact(
      ByteBuffer key, int offset, int length, TupleShape shape, int scanDirection) {
    StatusCode status = setRange(
        key, offset, length, shape, true,
        key, offset, length, shape, true, scanDirection);
    if (status.isOk()) kind = EXACT;
    return status;
  }

  public StatusCode setPrefix(
      ByteBuffer key, int offset, int length, TupleShape shape, int scanDirection) {
    StatusCode status = setExact(key, offset, length, shape, scanDirection);
    if (status.isOk()) kind = PREFIX;
    return status;
  }

  public StatusCode setRange(
      ByteBuffer low, int lowOffset, int lowLength, TupleShape lowShape, boolean lowInclusive,
      ByteBuffer high, int highOffset, int highLength, TupleShape highShape,
      boolean highInclusive, int scanDirection) {
    if ((scanDirection != FORWARD && scanDirection != REVERSE)
        || !validSide(low, lowOffset, lowLength, lowShape)
        || !validSide(high, highOffset, highLength, highShape)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    lower = low;
    lowerOffset = lowOffset;
    lowerLength = lowLength;
    lowerShape = lowShape;
    lowerInclusive = lowInclusive;
    upper = high;
    upperOffset = highOffset;
    upperLength = highLength;
    upperShape = highShape;
    upperInclusive = highInclusive;
    direction = scanDirection;
    kind = RANGE;
    return StatusCode.OK;
  }

  private static boolean validSide(ByteBuffer key, int offset, int length, TupleShape shape) {
    return key == null ? offset == 0 && length == 0 && shape == null
        : offset >= 0 && length > 0 && key.limit() - offset >= length && shape != null;
  }

  public ByteBuffer lowerKey() { return lower; }
  public int lowerOffset() { return lowerOffset; }
  public int lowerLength() { return lowerLength; }
  public TupleShape lowerShape() { return lowerShape; }
  public boolean lowerInclusive() { return lowerInclusive; }
  public ByteBuffer upperKey() { return upper; }
  public int upperOffset() { return upperOffset; }
  public int upperLength() { return upperLength; }
  public TupleShape upperShape() { return upperShape; }
  public boolean upperInclusive() { return upperInclusive; }
  public int direction() { return direction; }
}
