package io.riverdb.base.collection;

/** Checked geometric capacity selection shared by bounded primitive containers. */
public final class BoundedArrayGrowth {
  private BoundedArrayGrowth() {
  }

  /**
   * Returns a capacity in {@code [required, maximum]}, or {@code -1} when the inputs are invalid.
   * Existing sufficient high-water capacity is returned unchanged, even when a caller narrows its
   * current semantic maximum.
   */
  public static int capacity(int current, int required, int maximum, int initial) {
    if (current < 0 || required < 0 || maximum < 0 || initial < 1 || required > maximum) {
      return -1;
    }
    if (current >= required) {
      return current;
    }
    int capacity = current == 0 ? Math.min(initial, maximum) : current;
    while (capacity < required) {
      int doubled = capacity <= maximum / 2 ? capacity * 2 : maximum;
      if (doubled <= capacity) {
        return required;
      }
      capacity = doubled;
    }
    return capacity;
  }
}
