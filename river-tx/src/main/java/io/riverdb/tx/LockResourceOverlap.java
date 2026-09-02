package io.riverdb.tx;

import io.riverdb.base.key.OrderedKey;
import io.riverdb.tx.api.lock.LockScope;

/** Allocation-free exact-resource and ordered-key interval overlap rules. */
final class LockResourceOverlap {
  private static final byte KEY_SCOPE = (byte) LockScope.KEY.ordinal();
  private static final byte RANGE_SCOPE = (byte) LockScope.RANGE.ordinal();

  private LockResourceOverlap() {
  }

  static boolean isValid(
      LockScope scope,
      long lowerSpace,
      long lowerKey,
      long upperSpace,
      long upperKey) {
    if (scope == LockScope.KEY) {
      return OrderedKey.isFiniteSpace(lowerSpace)
          && OrderedKey.equal(lowerSpace, lowerKey, upperSpace, upperKey);
    }
    if (scope != LockScope.RANGE) {
      return lowerSpace == 0 && upperSpace == 0;
    }
    boolean validUpper = OrderedKey.isFiniteSpace(upperSpace)
        || OrderedKey.isInfinity(upperSpace, upperKey);
    return OrderedKey.isFiniteSpace(lowerSpace)
        && validUpper
        && OrderedKey.lessThan(lowerSpace, lowerKey, upperSpace, upperKey);
  }

  static boolean same(
      byte leftScope,
      long leftLowerSpace,
      long leftLowerKey,
      long leftUpperSpace,
      long leftUpperKey,
      byte rightScope,
      long rightLowerSpace,
      long rightLowerKey,
      long rightUpperSpace,
      long rightUpperKey) {
    return leftScope == rightScope
        && OrderedKey.equal(
            leftLowerSpace, leftLowerKey, rightLowerSpace, rightLowerKey)
        && OrderedKey.equal(
            leftUpperSpace, leftUpperKey, rightUpperSpace, rightUpperKey);
  }

  static boolean overlaps(
      byte leftScope,
      long leftLowerSpace,
      long leftLowerKey,
      long leftUpperSpace,
      long leftUpperKey,
      byte rightScope,
      long rightLowerSpace,
      long rightLowerKey,
      long rightUpperSpace,
      long rightUpperKey) {
    if (leftScope == RANGE_SCOPE && rightScope == RANGE_SCOPE) {
      return OrderedKey.lessThan(
              leftLowerSpace, leftLowerKey, rightUpperSpace, rightUpperKey)
          && OrderedKey.lessThan(
              rightLowerSpace, rightLowerKey, leftUpperSpace, leftUpperKey);
    }
    if (leftScope == RANGE_SCOPE && rightScope == KEY_SCOPE) {
      return contains(
          leftLowerSpace,
          leftLowerKey,
          leftUpperSpace,
          leftUpperKey,
          rightLowerSpace,
          rightLowerKey);
    }
    if (leftScope == KEY_SCOPE && rightScope == RANGE_SCOPE) {
      return contains(
          rightLowerSpace,
          rightLowerKey,
          rightUpperSpace,
          rightUpperKey,
          leftLowerSpace,
          leftLowerKey);
    }
    return same(
        leftScope,
        leftLowerSpace,
        leftLowerKey,
        leftUpperSpace,
        leftUpperKey,
        rightScope,
        rightLowerSpace,
        rightLowerKey,
        rightUpperSpace,
        rightUpperKey);
  }

  private static boolean contains(
      long lowerSpace,
      long lowerKey,
      long upperSpace,
      long upperKey,
      long keySpace,
      long key) {
    return !OrderedKey.lessThan(keySpace, key, lowerSpace, lowerKey)
        && OrderedKey.lessThan(keySpace, key, upperSpace, upperKey);
  }
}
