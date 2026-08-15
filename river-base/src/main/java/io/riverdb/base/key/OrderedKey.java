package io.riverdb.base.key;

/** Allocation-free ordering for one primitive physical key pair. */
public final class OrderedKey {
  public static final int INFINITY_SPACE = Integer.MAX_VALUE;

  private OrderedKey() {
  }

  public static boolean isFiniteSpace(int space) {
    return space >= 0 && space < INFINITY_SPACE;
  }

  public static boolean isInfinity(int space, long key) {
    return space == INFINITY_SPACE && key == 0;
  }

  public static int compare(
      int leftSpace, long leftKey, int rightSpace, long rightKey) {
    int spaceComparison = Integer.compare(leftSpace, rightSpace);
    return spaceComparison != 0 ? spaceComparison : Long.compare(leftKey, rightKey);
  }

  public static boolean lessThan(
      int leftSpace, long leftKey, int rightSpace, long rightKey) {
    return compare(leftSpace, leftKey, rightSpace, rightKey) < 0;
  }

  public static boolean equal(
      int leftSpace, long leftKey, int rightSpace, long rightKey) {
    return leftSpace == rightSpace && leftKey == rightKey;
  }
}
