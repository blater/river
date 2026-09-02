package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleOrder;
import io.riverdb.base.tuple.TupleShape;

/** Allocation-free semantic comparison of contiguous or page-spanning encoded tuples. */
public final class TupleComparator {
  private final TupleInputCursor leftCursor = new TupleInputCursor();
  private final TupleInputCursor rightCursor = new TupleInputCursor();

  public StatusCode compare(
      TupleInput left,
      TupleInput right,
      TupleShape shape,
      TupleOrder order,
      boolean compareLogicalRowId,
      TupleComparison result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (shape == null || order == null || order.partCount() != shape.partCount()
        || !leftCursor.open(left, shape) || !rightCursor.open(right, shape)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int finalComparison = 0;
    for (int part = 0; part < shape.partCount(); part++) {
      int descriptor = shape.descriptorAt(part);
      if (!leftCursor.next(descriptor) || !rightCursor.next(descriptor)) {
        return StatusCode.CORRUPTION;
      }
      int comparison = comparePart(part, order);
      if (finalComparison == 0) finalComparison = comparison;
    }
    if (!leftCursor.complete() || !rightCursor.complete()) return StatusCode.CORRUPTION;
    if (finalComparison == 0 && compareLogicalRowId) {
      if (!leftCursor.physical() || !rightCursor.physical()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      result.set(Long.compareUnsigned(
          leftCursor.logicalRowId(), rightCursor.logicalRowId()));
    } else {
      result.set(finalComparison);
    }
    return StatusCode.OK;
  }

  private int comparePart(int part, TupleOrder order) {
    boolean leftNull = leftCursor.marker() == TupleKeyCodec.NULL_VALUE;
    boolean rightNull = rightCursor.marker() == TupleKeyCodec.NULL_VALUE;
    if (leftNull || rightNull) {
      if (leftNull == rightNull) return 0;
      return leftNull == order.nullsFirst(part) ? -1 : 1;
    }
    int comparison = compareBytes(
        leftCursor, leftCursor.valueStart(), leftCursor.valueEnd(),
        rightCursor, rightCursor.valueStart(), rightCursor.valueEnd());
    return order.descending(part) ? -comparison : comparison;
  }

  private static int compareBytes(
      TupleInputCursor left, int leftStart, int leftEnd,
      TupleInputCursor right, int rightStart, int rightEnd) {
    int shared = Math.min(leftEnd - leftStart, rightEnd - rightStart);
    for (int index = 0; index < shared; index++) {
      int comparison = Integer.compare(
          left.byteAt(leftStart + index), right.byteAt(rightStart + index));
      if (comparison != 0) return comparison;
    }
    return Integer.compare(leftEnd - leftStart, rightEnd - rightStart);
  }
}
