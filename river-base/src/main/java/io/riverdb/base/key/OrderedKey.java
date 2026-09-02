package io.riverdb.base.key;

/** Allocation-free ordering for one primitive physical key pair. */
public final class OrderedKey {
  /** Explicit fence above every finite non-negative object/key space. */
  public static final long INFINITY_SPACE = -1;

  private OrderedKey() {
  }

  public static boolean isFiniteSpace(long space) {
    return space >= 0;
  }

  public static boolean isInfinity(long space, long key) {
    return space == INFINITY_SPACE && key == 0;
  }

  public static int compare(
      long leftSpace, long leftKey, long rightSpace, long rightKey) {
    boolean leftInfinity = isInfinity(leftSpace, leftKey);
    boolean rightInfinity = isInfinity(rightSpace, rightKey);
    if (leftInfinity != rightInfinity) return leftInfinity ? 1 : -1;
    int spaceComparison = Long.compare(leftSpace, rightSpace);
    return spaceComparison != 0 ? spaceComparison : Long.compare(leftKey, rightKey);
  }

  public static boolean lessThan(
      long leftSpace, long leftKey, long rightSpace, long rightKey) {
    return compare(leftSpace, leftKey, rightSpace, rightKey) < 0;
  }

  public static boolean equal(
      long leftSpace, long leftKey, long rightSpace, long rightKey) {
    return leftSpace == rightSpace && leftKey == rightKey;
  }
}
