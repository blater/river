package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Fully lazy adaptive radix directory over nonnegative 64-bit ordinals. */
final class LockRadixDirectory {
  private static final int SHIFT = 8;
  private static final int ENTRIES = 1 << SHIFT;
  private static final int MASK = ENTRIES - 1;
  private static final int MAX_SHIFT = 56;
  private static final long NODE_BYTES = 24L + 8L * ENTRIES;
  static final long BASE_BYTES = NODE_BYTES;

  private final LockSegmentArena arena;
  private Object[] baseRoot;
  private Object[] root;
  private int rootShift;

  LockRadixDirectory(LockSegmentArena owner) {
    arena = owner;
    if (!owner.reserve(BASE_BYTES).isOk()) {
      throw new IllegalArgumentException("lock envelope too small");
    }
    try {
      baseRoot = new Object[ENTRIES];
      root = baseRoot;
    } catch (OutOfMemoryError failure) {
      owner.release(BASE_BYTES);
      throw new IllegalArgumentException("lock directory allocation failed", failure);
    }
  }

  Object get(long ordinal) {
    if (ordinal < 0) return null;
    if (rootShift < MAX_SHIFT && (ordinal >>> rootShift) >= ENTRIES) return null;
    Object[] node = root;
    for (int shift = rootShift; shift > 0; shift -= SHIFT) {
      Object child = node[index(ordinal, shift)];
      if (!(child instanceof Object[])) return null;
      node = (Object[]) child;
    }
    return node[(int) ordinal & MASK];
  }

  StatusCode reserve(long ordinal) {
    if (ordinal < 0) return StatusCode.RESOURCE_EXHAUSTED;
    int requiredShift = requiredShift(ordinal);
    Object[] candidateRoot = root;
    int candidateShift = rootShift;
    int addedParents = 0;
    while (candidateShift < requiredShift) {
      Object[] parent = allocateNode();
      if (parent == null) {
        releaseNodes(addedParents);
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      parent[0] = candidateRoot;
      candidateRoot = parent;
      candidateShift += SHIFT;
      addedParents++;
    }

    Object[] node = candidateRoot;
    for (int shift = candidateShift; shift > 0; shift -= SHIFT) {
      int slot = index(ordinal, shift);
      Object child = node[slot];
      if (child == null) {
        child = allocateNode();
        if (child == null) {
          prune(candidateRoot, ordinal, candidateShift);
          releaseNodes(addedParents);
          return StatusCode.RESOURCE_EXHAUSTED;
        }
        node[slot] = child;
      }
      node = (Object[]) child;
    }

    if (candidateRoot != root) {
      root = candidateRoot;
      rootShift = candidateShift;
    }
    return StatusCode.OK;
  }

  void set(long ordinal, Object value) {
    Object[] node = root;
    for (int shift = rootShift; shift > 0; shift -= SHIFT) {
      node = (Object[]) node[index(ordinal, shift)];
    }
    node[(int) ordinal & MASK] = value;
  }

  void remove(long ordinal) {
    if (ordinal < 0) return;
    set(ordinal, null);
    prune(root, ordinal, rootShift);
    collapseRoot();
  }

  private Object[] allocateNode() {
    if (!arena.reserve(NODE_BYTES).isOk()) return null;
    try {
      return new Object[ENTRIES];
    } catch (OutOfMemoryError failure) {
      arena.release(NODE_BYTES);
      return null;
    }
  }

  private void releaseNodes(int count) {
    arena.release(count * NODE_BYTES);
  }

  private boolean prune(Object[] node, long ordinal, int shift) {
    int slot = index(ordinal, shift);
    if (shift > 0) {
      Object child = node[slot];
      if (child instanceof Object[] && prune((Object[]) child, ordinal, shift - SHIFT)) {
        node[slot] = null;
        arena.release(NODE_BYTES);
      }
    }
    if (node == baseRoot) return false;
    for (Object child : node) if (child != null) return false;
    return true;
  }

  private void collapseRoot() {
    while (rootShift > 0) {
      Object child = root[0];
      if (!(child instanceof Object[])) return;
      for (int index = 1; index < ENTRIES; index++) {
        if (root[index] != null) return;
      }
      root = (Object[]) child;
      rootShift -= SHIFT;
      arena.release(NODE_BYTES);
    }
  }

  private static int requiredShift(long ordinal) {
    int shift = 0;
    while (shift < MAX_SHIFT && (ordinal >>> (shift + SHIFT)) != 0) {
      shift += SHIFT;
    }
    return shift;
  }

  private static int index(long ordinal, int shift) {
    return (int) (ordinal >>> shift) & MASK;
  }
}
