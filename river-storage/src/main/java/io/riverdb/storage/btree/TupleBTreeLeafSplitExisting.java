package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
import java.nio.ByteBuffer;

/** Splits a full leaf before retrying an inline entry that needs a third sibling. */
final class TupleBTreeLeafSplitExisting {
  private TupleBTreeLeafSplitExisting() { }

  static StatusCode split(
      ByteBuffer source, int sourceStart, ByteBuffer left, int leftStart,
      ByteBuffer right, int rightStart, int leftPageId, int rightPageId,
      long schemaId, TupleShape shape, TupleBTreeWorkspace workspace,
      TupleBTreeSplitResult result) {
    StatusCode status = TupleBTreePageAdmission.validate(
        source, sourceStart, schemaId, shape, TupleBTreePageCodec.TYPE_LEAF, workspace);
    if (!status.isOk()) return status;
    int count = workspace.header.entryCount();
    if (count < 2) return StatusCode.RESOURCE_EXHAUSTED;
    int totalBytes = 0;
    for (int index = 0; index < count; index++) {
      if (!TupleBTreePageSupport.readLeaf(source, sourceStart, index, workspace)) {
        return StatusCode.INVARIANT_BROKEN;
      }
      totalBytes += entryBytes(workspace);
    }
    int leftBytes = 0;
    int splitAt = 0;
    int imbalance = Integer.MAX_VALUE;
    for (int index = 1; index < count; index++) {
      if (!TupleBTreePageSupport.readLeaf(source, sourceStart, index - 1, workspace)) {
        return StatusCode.INVARIANT_BROKEN;
      }
      leftBytes += entryBytes(workspace);
      if (!TupleBTreePageSupport.readLeaf(source, sourceStart, index, workspace)) {
        return StatusCode.INVARIANT_BROKEN;
      }
      int leftUsed = TupleBTreeSplitOccupancy.bytes(
          TupleBTreePageCodec.TYPE_LEAF, index, leftBytes, workspace.leaf.keyLength());
      int rightUsed = TupleBTreeSplitOccupancy.bytes(
          TupleBTreePageCodec.TYPE_LEAF, count - index, totalBytes - leftBytes,
          workspace.header.highKeyLength());
      int difference = TupleBTreeSplitOccupancy.imbalance(leftUsed, rightUsed);
      if (TupleBTreeSplitOccupancy.fits(leftUsed)
          && TupleBTreeSplitOccupancy.fits(rightUsed) && difference < imbalance) {
        splitAt = index;
        imbalance = difference;
      }
    }
    if (splitAt == 0 || !TupleBTreePageSupport.readLeaf(
        source, sourceStart, splitAt, workspace)) return StatusCode.INVARIANT_BROKEN;
    status = TupleBTreeLeafSplitOutput.initialize(
        source, sourceStart, left, leftStart, right, rightStart,
        leftPageId, rightPageId, schemaId, shape, source,
        sourceStart + workspace.leaf.keyOffset(), workspace.leaf.keyLength(), workspace);
    for (int index = 0; status.isOk() && index < count; index++) {
      boolean toLeft = index < splitAt;
      status = TupleBTreePageSupport.appendLeafSource(
          source, sourceStart, toLeft ? left : right,
          toLeft ? leftStart : rightStart, shape, index, workspace);
    }
    if (status.isOk()) status = TupleBTreeLeafSplitOutput.validate(
        left, leftStart, right, rightStart, schemaId, shape, workspace);
    if (!status.isOk()) return status;
    if (!TupleBTreePageSupport.readLeaf(right, rightStart, 0, workspace)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    result.set(right, rightStart + workspace.leaf.keyOffset(),
        workspace.leaf.keyLength(), splitAt, count - splitAt);
    return StatusCode.OK;
  }

  private static int entryBytes(TupleBTreeWorkspace workspace) {
    return workspace.leaf.keyLength()
        + (workspace.leaf.overflowPageId() == 0 ? workspace.leaf.valueLength() : 0);
  }
}
