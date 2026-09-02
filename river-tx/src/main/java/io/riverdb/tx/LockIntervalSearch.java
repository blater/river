package io.riverdb.tx;

/** Pruned stackless overlap search over the shared interval tree. */
final class LockIntervalSearch {
  private final LockIntervalStorage nodes;

  LockIntervalSearch(LockIntervalStorage storage) { nodes = storage; }

  long first(long subtree, LockIntervalCursor query, LockIntervalCursor accounting) {
    long node = subtree;
    if (!nodes.maximumAfter(node, query)) return -1;
    while (node >= 0) {
      if (accounting != null) accounting.visited();
      long left = nodes.left(node);
      if (nodes.maximumAfter(left, query)) node = left;
      else if (!nodes.lowerBeforeUpper(node, query)) return -1;
      else if (nodes.overlaps(node, query)) return node;
      else {
        node = nodes.right(node);
        if (!nodes.maximumAfter(node, query)) return -1;
      }
    }
    return -1;
  }

  long next(long current, LockIntervalCursor query, LockIntervalCursor accounting) {
    long found = first(nodes.right(current), query, accounting);
    if (found >= 0) return found;
    long child = current;
    for (long node = nodes.parent(current); node >= 0; node = nodes.parent(node)) {
      if (accounting != null) accounting.visited();
      if (nodes.left(node) == child) {
        if (!nodes.lowerBeforeUpper(node, query)) return -1;
        if (nodes.overlaps(node, query)) return node;
        found = first(nodes.right(node), query, accounting);
        if (found >= 0) return found;
      }
      child = node;
    }
    return -1;
  }
}
