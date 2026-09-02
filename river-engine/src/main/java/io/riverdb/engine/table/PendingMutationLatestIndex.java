package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;

/** Session-owned chunked AVL index from a mutation resource to its latest buffer position. */
final class PendingMutationLatestIndex {
  private final PendingMutationLatestNodes nodes;
  private int root;
  private int count;

  PendingMutationLatestIndex(int capacity) {
    nodes = new PendingMutationLatestNodes(capacity);
  }

  PendingMutationLatestIndex(
      int capacity, PendingMutationLatestChunkAllocator allocator) {
    nodes = new PendingMutationLatestNodes(capacity, allocator);
  }

  StatusCode reserve(int requiredEntries) {
    return nodes.reserve(requiredEntries);
  }

  int find(long space, long key) {
    int node = root;
    while (node != 0) {
      int comparison = compare(space, key, node);
      if (comparison == 0) return nodes.latest(node);
      node = comparison < 0 ? nodes.left(node) : nodes.right(node);
    }
    return -1;
  }

  int next(IndexedScanCursor cursor) {
    int node = root;
    int selected = 0;
    while (node != 0) {
      long space = nodes.space(node);
      long key = nodes.key(node);
      if (OrderedKey.compare(space, key, cursor.lowerSpace(), cursor.lowerKey()) < 0
          || !cursor.afterLastReturned(space, key)) {
        node = nodes.right(node);
      } else if (OrderedKey.compare(
          space, key, cursor.upperSpace(), cursor.upperKey()) >= 0) {
        node = nodes.left(node);
      } else {
        selected = node;
        node = nodes.left(node);
      }
    }
    return selected == 0 ? -1 : nodes.latest(selected);
  }

  void put(long space, long key, int index) {
    int parent = 0;
    int node = root;
    int comparison = 0;
    while (node != 0) {
      comparison = compare(space, key, node);
      if (comparison == 0) {
        nodes.latest(node, index);
        return;
      }
      parent = node;
      node = comparison < 0 ? nodes.left(node) : nodes.right(node);
    }
    int inserted = ++count;
    nodes.initialize(inserted, space, key, index, parent);
    if (parent == 0) root = inserted;
    else if (comparison < 0) nodes.left(parent, inserted);
    else nodes.right(parent, inserted);
    rebalance(parent);
  }

  void rebuild(PendingMutationMetadata metadata, int mutationCount) {
    root = 0;
    count = 0;
    for (int index = 0; index < mutationCount; index++) {
      put(metadata.spaceAt(index), metadata.keyAt(index), index);
    }
  }

  long accountedBytes() { return nodes.accountedBytes(); }

  long accountedBytesForEntries(int requiredEntries) {
    return nodes.accountedBytesForEntries(requiredEntries);
  }

  int height() { return height(root); }

  void release() {
    nodes.release();
    root = 0;
    count = 0;
  }

  private void rebalance(int node) {
    while (node != 0) {
      updateHeight(node);
      int replacement = node;
      if (balance(node) > 1) {
        if (balance(nodes.left(node)) < 0) rotateLeft(nodes.left(node));
        replacement = rotateRight(node);
      } else if (balance(node) < -1) {
        if (balance(nodes.right(node)) > 0) rotateRight(nodes.right(node));
        replacement = rotateLeft(node);
      }
      node = nodes.parent(replacement);
    }
  }

  private int rotateLeft(int node) {
    int replacement = nodes.right(node);
    int middle = nodes.left(replacement);
    replace(node, replacement);
    nodes.right(node, middle);
    if (middle != 0) nodes.parent(middle, node);
    nodes.left(replacement, node);
    nodes.parent(node, replacement);
    updateHeight(node);
    updateHeight(replacement);
    return replacement;
  }

  private int rotateRight(int node) {
    int replacement = nodes.left(node);
    int middle = nodes.right(replacement);
    replace(node, replacement);
    nodes.left(node, middle);
    if (middle != 0) nodes.parent(middle, node);
    nodes.right(replacement, node);
    nodes.parent(node, replacement);
    updateHeight(node);
    updateHeight(replacement);
    return replacement;
  }

  private void replace(int node, int replacement) {
    int owner = nodes.parent(node);
    nodes.parent(replacement, owner);
    if (owner == 0) root = replacement;
    else if (nodes.left(owner) == node) nodes.left(owner, replacement);
    else nodes.right(owner, replacement);
  }

  private int compare(long space, long key, int node) {
    return OrderedKey.compare(space, key, nodes.space(node), nodes.key(node));
  }

  private int balance(int node) {
    return height(nodes.left(node)) - height(nodes.right(node));
  }

  private void updateHeight(int node) {
    nodes.height(node, Math.max(height(nodes.left(node)), height(nodes.right(node))) + 1);
  }

  private int height(int node) {
    return node == 0 ? 0 : nodes.height(node);
  }
}
