package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Fully lazy fixed-fanout radix directory over nonnegative 64-bit ordinals. */
final class LockRadixDirectory {
  private static final int SHIFT = 8;
  private static final int ENTRIES = 1 << SHIFT;
  private static final int MASK = ENTRIES - 1;
  private static final int ROOT_ENTRIES = 1 << 7;
  private static final long ROOT_BYTES = 24L + 8L * ROOT_ENTRIES;
  private static final long NODE_BYTES = 24L + 8L * ENTRIES;
  static final long MAXIMUM_NEW_PATH_BYTES = 7 * NODE_BYTES;
  private final Object[] root;
  private final LockSegmentArena arena;

  LockRadixDirectory(LockSegmentArena owner) {
    arena = owner;
    if (!owner.reserve(ROOT_BYTES).isOk()) {
      throw new IllegalArgumentException("lock envelope too small");
    }
    try {
      root = new Object[ROOT_ENTRIES];
    } catch (OutOfMemoryError failure) {
      owner.release(ROOT_BYTES);
      throw new IllegalArgumentException("lock directory allocation failed", failure);
    }
  }

  Object get(long ordinal) {
    if (ordinal < 0) return null;
    Object[] node = root;
    for (int shift = 56; shift > 0; shift -= SHIFT) {
      Object child = node[index(ordinal, shift)];
      if (!(child instanceof Object[])) return null;
      node = (Object[]) child;
    }
    return node[(int) ordinal & MASK];
  }

  StatusCode reserve(long ordinal) {
    if (ordinal < 0) return StatusCode.RESOURCE_EXHAUSTED;
    Object[] node = root;
    for (int shift = 56; shift > 0; shift -= SHIFT) {
      int index = index(ordinal, shift);
      Object child = node[index];
      if (child == null) {
        StatusCode status = arena.reserve(NODE_BYTES);
        if (!status.isOk()) {
          prune(root, ordinal, 56);
          return status;
        }
        try {
          child = new Object[ENTRIES];
          node[index] = child;
        } catch (OutOfMemoryError failure) {
          arena.release(NODE_BYTES);
          prune(root, ordinal, 56);
          return StatusCode.RESOURCE_EXHAUSTED;
        }
      }
      node = (Object[]) child;
    }
    return StatusCode.OK;
  }

  void set(long ordinal, Object value) {
    Object[] node = root;
    for (int shift = 56; shift > 0; shift -= SHIFT) {
      node = (Object[]) node[index(ordinal, shift)];
    }
    node[(int) ordinal & MASK] = value;
  }

  void remove(long ordinal) {
    set(ordinal, null);
    prune(root, ordinal, 56);
  }

  private boolean prune(Object[] node, long ordinal, int shift) {
    int index = index(ordinal, shift);
    if (shift > 0) {
      Object child = node[index];
      if (child instanceof Object[] && prune((Object[]) child, ordinal, shift - SHIFT)) {
        node[index] = null;
        arena.release(NODE_BYTES);
      }
    }
    for (Object child : node) if (child != null) return false;
    return node != root;
  }

  private static int index(long ordinal, int shift) {
    return (int) (ordinal >>> shift) & MASK;
  }

}
