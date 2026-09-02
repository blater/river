package io.riverdb.storage.btree;

import java.nio.ByteBuffer;

/** Chooses a byte-balanced promoted separator with fitting internal outputs. */
final class TupleBTreeInternalSplitPoint {
  private TupleBTreeInternalSplitPoint() { }

  static int choose(
      ByteBuffer source, int start, int keyLength, int insertion,
      TupleBTreeWorkspace workspace) {
    int total = workspace.header.entryCount() + 1;
    int totalKeyBytes = keyLength;
    for (int index = 0; index < total - 1; index++) {
      TupleBTreePageSupport.readInternal(source, start, index, workspace);
      totalKeyBytes += workspace.internal.keyLength();
    }
    int oldFenceBytes = workspace.header.highKeyLength();
    int leftKeyBytes = 0;
    int selected = -1;
    int selectedImbalance = Integer.MAX_VALUE;
    for (int promoted = 1; promoted < total - 1; promoted++) {
      leftKeyBytes += mergedLength(
          source, start, keyLength, insertion, promoted - 1, workspace);
      int promotedBytes = mergedLength(
          source, start, keyLength, insertion, promoted, workspace);
      int leftBytes = TupleBTreeSplitOccupancy.bytes(
          promoted, leftKeyBytes, promotedBytes);
      int rightBytes = TupleBTreeSplitOccupancy.bytes(
          total - promoted - 1,
          totalKeyBytes - leftKeyBytes - promotedBytes, oldFenceBytes);
      int imbalance = TupleBTreeSplitOccupancy.imbalance(leftBytes, rightBytes);
      if (TupleBTreeSplitOccupancy.fits(leftBytes)
          && TupleBTreeSplitOccupancy.fits(rightBytes)
          && imbalance < selectedImbalance) {
        selected = promoted;
        selectedImbalance = imbalance;
      }
    }
    return selected;
  }

  private static int mergedLength(
      ByteBuffer source, int start, int keyLength, int insertion,
      int mergedIndex, TupleBTreeWorkspace workspace) {
    if (mergedIndex == insertion) return keyLength;
    TupleBTreePageSupport.readInternal(
        source, start, mergedIndex < insertion ? mergedIndex : mergedIndex - 1, workspace);
    return workspace.internal.keyLength();
  }
}
