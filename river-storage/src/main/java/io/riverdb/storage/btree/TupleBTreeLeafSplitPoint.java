package io.riverdb.storage.btree;

import io.riverdb.format.btree.TupleBTreePageCodec;
import java.nio.ByteBuffer;

/** Chooses a byte-balanced leaf boundary whose duplicated fence fits both pages. */
final class TupleBTreeLeafSplitPoint {
  private TupleBTreeLeafSplitPoint() { }

  static int choose(
      ByteBuffer source, int start, ByteBuffer key, int keyOffset, int keyLength,
      int insertion, TupleBTreeWorkspace workspace) {
    return choose(source, start, key, keyOffset, keyLength, 0, insertion, workspace);
  }

  static int choose(
      ByteBuffer source, int start, ByteBuffer key, int keyOffset, int keyLength,
      int insertedInlineLength, int insertion, TupleBTreeWorkspace workspace) {
    int total = workspace.header.entryCount() + 1;
    int totalEntryBytes = keyLength + insertedInlineLength;
    for (int index = 0; index < total - 1; index++) {
      if (!TupleBTreePageSupport.readLeaf(source, start, index, workspace)) return -1;
      totalEntryBytes += workspace.leaf.keyLength()
          + (workspace.leaf.overflowPageId() == 0 ? workspace.leaf.valueLength() : 0);
    }
    int oldFenceBytes = workspace.header.highKeyLength();
    int leftEntryBytes = 0;
    int selected = 0;
    int selectedImbalance = Integer.MAX_VALUE;
    for (int split = 1; split < total; split++) {
      int leftLength = mergedLength(
          source, start, keyLength + insertedInlineLength, insertion, split - 1, workspace);
      if (leftLength < 0) return -1;
      leftEntryBytes += leftLength;
      int fenceBytes = mergedKeyLength(
          source, start, keyLength, insertion, split, workspace);
      if (fenceBytes < 0) return -1;
      int leftBytes = TupleBTreeSplitOccupancy.bytes(
          TupleBTreePageCodec.TYPE_LEAF, split, leftEntryBytes, fenceBytes);
      int rightBytes = TupleBTreeSplitOccupancy.bytes(
          TupleBTreePageCodec.TYPE_LEAF,
          total - split, totalEntryBytes - leftEntryBytes, oldFenceBytes);
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
    return workspace.leaf.keyLength()
        + (workspace.leaf.overflowPageId() == 0 ? workspace.leaf.valueLength() : 0);
  }

  private static int mergedKeyLength(
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
