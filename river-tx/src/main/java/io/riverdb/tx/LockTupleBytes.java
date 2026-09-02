package io.riverdb.tx;

import java.nio.ByteBuffer;

/** Allocation-free unsigned byte comparison with variable-length endpoint cuts. */
final class LockTupleBytes {
  private static final int CONTINUE = 2;

  private LockTupleBytes() {
  }

  static int compare(
      byte[] left, int leftLength, int leftKind,
      byte[] right, int rightLength, int rightKind) {
    int terminal = terminal(leftKind, rightKind);
    if (terminal != CONTINUE) return terminal;
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int compared = Integer.compare(
          Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
      if (compared != 0) return compared;
    }
    return prefix(leftLength, leftKind, rightLength, rightKind);
  }

  static int compare(
      byte[] left, int leftLength, int leftKind,
      ByteBuffer right, int rightOffset, int rightLength, int rightKind) {
    int terminal = terminal(leftKind, rightKind);
    if (terminal != CONTINUE) return terminal;
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int compared = Integer.compare(
          Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right.get(rightOffset + index)));
      if (compared != 0) return compared;
    }
    return prefix(leftLength, leftKind, rightLength, rightKind);
  }

  static int compare(
      ByteBuffer left, int leftOffset, int leftLength, int leftKind,
      ByteBuffer right, int rightOffset, int rightLength, int rightKind) {
    int terminal = terminal(leftKind, rightKind);
    if (terminal != CONTINUE) return terminal;
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int compared = Integer.compare(Byte.toUnsignedInt(left.get(leftOffset + index)),
          Byte.toUnsignedInt(right.get(rightOffset + index)));
      if (compared != 0) return compared;
    }
    return prefix(leftLength, leftKind, rightLength, rightKind);
  }

  private static int terminal(int leftKind, int rightKind) {
    if (leftKind == rightKind && (leftKind == LockTupleEndpoint.NEGATIVE_INFINITY
        || leftKind == LockTupleEndpoint.POSITIVE_INFINITY)) return 0;
    if (leftKind == LockTupleEndpoint.NEGATIVE_INFINITY
        || rightKind == LockTupleEndpoint.POSITIVE_INFINITY) return -1;
    if (leftKind == LockTupleEndpoint.POSITIVE_INFINITY
        || rightKind == LockTupleEndpoint.NEGATIVE_INFINITY) return 1;
    return CONTINUE;
  }

  private static int prefix(int leftLength, int leftKind, int rightLength, int rightKind) {
    if (leftLength == rightLength) return Integer.compare(leftKind, rightKind);
    if (leftLength < rightLength) return leftKind == LockTupleEndpoint.AFTER_PREFIX ? 1 : -1;
    return rightKind == LockTupleEndpoint.AFTER_PREFIX ? -1 : 1;
  }
}
