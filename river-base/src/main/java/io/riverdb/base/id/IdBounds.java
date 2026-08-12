package io.riverdb.base.id;

final class IdBounds {
  private IdBounds() {
  }

  static int nonNegative(String name, int value) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be non-negative");
    }
    return value;
  }

  static long nonNegative(String name, long value) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be non-negative");
    }
    return value;
  }

  static int positive(String name, int value) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  static long positive(String name, long value) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
