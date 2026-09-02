package io.riverdb.tx;

/** AVL insertion, removal and augmentation repair over intrusive interval nodes. */
final class LockIntervalMutations {
  private final LockIntervalStorage nodes;

  LockIntervalMutations(LockIntervalStorage storage) { nodes = storage; }

  void add(long slot) {
    nodes.initialize(slot);
    if (nodes.root < 0) {
      nodes.root = slot;
      return;
    }
    long parent = locateParent(slot);
    nodes.parent(slot, parent);
    if (nodes.compare(slot, parent) < 0) nodes.left(parent, slot);
    else nodes.right(parent, slot);
    rebalance(parent);
  }

  void remove(long slot) {
    long repair;
    if (nodes.left(slot) < 0) {
      repair = nodes.parent(slot);
      transplant(slot, nodes.right(slot));
    } else if (nodes.right(slot) < 0) {
      repair = nodes.parent(slot);
      transplant(slot, nodes.left(slot));
    } else {
      long successor = minimum(nodes.right(slot));
      repair = detachSuccessor(slot, successor);
      transplant(slot, successor);
      nodes.left(successor, nodes.left(slot));
      nodes.parent(nodes.left(successor), successor);
      nodes.update(successor);
    }
    nodes.clear(slot);
    rebalance(repair);
  }

  private long locateParent(long slot) {
    long node = nodes.root;
    while (true) {
      long child = nodes.compare(slot, node) < 0 ? nodes.left(node) : nodes.right(node);
      if (child < 0) return node;
      node = child;
    }
  }

  private long detachSuccessor(long removed, long successor) {
    if (nodes.parent(successor) == removed) return successor;
    long repair = nodes.parent(successor);
    transplant(successor, nodes.right(successor));
    nodes.right(successor, nodes.right(removed));
    nodes.parent(nodes.right(successor), successor);
    return repair;
  }

  private void rebalance(long node) {
    while (node >= 0) {
      nodes.update(node);
      int balance = nodes.height(nodes.left(node)) - nodes.height(nodes.right(node));
      long top = node;
      if (balance > 1) top = repairLeftHeavy(node);
      else if (balance < -1) top = repairRightHeavy(node);
      node = nodes.parent(top);
    }
  }

  private long repairLeftHeavy(long node) {
    long child = nodes.left(node);
    if (nodes.height(nodes.left(child)) < nodes.height(nodes.right(child))) rotateLeft(child);
    return rotateRight(node);
  }

  private long repairRightHeavy(long node) {
    long child = nodes.right(node);
    if (nodes.height(nodes.right(child)) < nodes.height(nodes.left(child))) rotateRight(child);
    return rotateLeft(node);
  }

  private long rotateLeft(long node) {
    long top = nodes.right(node);
    long middle = nodes.left(top);
    replaceParent(node, top);
    nodes.left(top, node);
    nodes.parent(node, top);
    nodes.right(node, middle);
    nodes.parent(middle, node);
    nodes.update(node);
    nodes.update(top);
    return top;
  }

  private long rotateRight(long node) {
    long top = nodes.left(node);
    long middle = nodes.right(top);
    replaceParent(node, top);
    nodes.right(top, node);
    nodes.parent(node, top);
    nodes.left(node, middle);
    nodes.parent(middle, node);
    nodes.update(node);
    nodes.update(top);
    return top;
  }

  private void transplant(long node, long replacement) { replaceParent(node, replacement); }

  private void replaceParent(long node, long replacement) {
    long parent = nodes.parent(node);
    nodes.parent(replacement, parent);
    if (parent < 0) nodes.root = replacement;
    else if (nodes.left(parent) == node) nodes.left(parent, replacement);
    else nodes.right(parent, replacement);
  }

  private long minimum(long node) {
    while (nodes.left(node) >= 0) node = nodes.left(node);
    return node;
  }
}
