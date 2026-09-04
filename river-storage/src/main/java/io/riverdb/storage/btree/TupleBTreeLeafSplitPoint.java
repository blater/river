package io.riverdb.storage.btree;

import java.nio.ByteBuffer;

/** Chooses a byte-balanced leaf boundary whose duplicated fence fits both pages. */
final class TupleBTreeLeafSplitPoint {
  private TupleBTreeLeafSplitPoint() { }

  static int choose(
      ByteBuffer source, int start, ByteBuffer key, int keyOffset, int keyLength,
      int insertion, TupleBTreeWorkspace workspace) {
    int total = workspace.header.entryCount() + 1;
    int totalKeyBytes = keyLength;
    for (int index = 0; index < total - 1; index++) {
      if (!TupleBTreePageSupport.readLeaf(source, start, index, workspace)) return -1;
      totalKeyBytes += workspace.leaf.keyLength();
    }
    int oldFenceBytes = workspace.header.highKeyLength();
    int leftKeyBytes = 0;
    int selected = 0;
    int selectedImbalance = Integer.MAX_VALUE;
    for (int split = 1; split < total; split++) {
      int leftLength = mergedLength(
          source, start, keyLength, insertion, split - 1, workspace);
      if (leftLength < 0) return -1;
      leftKeyBytes += leftLength;
      int fenceBytes = mergedLength(
          source, start, keyLength, insertion, split, workspace);
      if (fenceBytes < 0) return -1;
      int leftBytes = TupleBTreeSplitOccupancy.bytes(split, leftKeyBytes, fenceBytes);
      int rightBytes = TupleBTreeSplitOccupancy.bytes(
          total - split, totalKeyBytes - leftKeyBytes, oldFenceBytes);
      int imbalance = TupleBTreeSplitOccupancy.imbalance(leftBytes, rightBytes);
      if (TupleBTreeSplitOccupancy.fits(leftBytes)
          && TupleBTreeSplitOccupancy.fits(rightBytes)
          && imbalance < selectedImbalance) {
        selected = split;
        selectedImbalance = imbalance;
      }
    }
    return selected;
  }

  private static int mergedLength(
      ByteBuffer source, int start, int keyLength, int insertion,
      int mergedIndex, TupleBTreeWorkspace workspace) {
    if (mergedIndex == insertion) return keyLength;
    if (!TupleBTreePageSupport.readLeaf(
        source, start, mergedIndex < insertion ? mergedIndex : mergedIndex - 1, workspace)) {
      return -1;
    }
    return workspace.leaf.keyLength();
  }
}
