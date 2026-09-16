package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import java.nio.ByteBuffer;

/** Unpublished two-page leaf split over caller-owned output payloads. */
final class TupleBTreeLeafSplit {
  private TupleBTreeLeafSplit() { }

  static StatusCode splitInsert(
      ByteBuffer source, int sourceStart,
      ByteBuffer left, int leftStart,
      ByteBuffer right, int rightStart,
      int leftPageId, int rightPageId, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace, TupleBTreeSplitResult result) {
    StatusCode status = TupleBTreeLeafSplitAdmission.prepare(
        source, sourceStart, left, leftStart, right, rightStart,
        leftPageId, rightPageId, schemaId, shape,
        key, keyOffset, keyLength, workspace, result);
    if (!status.isOk()) return status;
    int insertion = TupleBTreePageSupport.lowerBoundLeaf(
        source, sourceStart, key, keyOffset, keyLength, workspace);
    if (insertion < 0) return StatusCode.INVARIANT_BROKEN;
    int equality = TupleBTreeLeafSplitAdmission.equalAt(
        source, sourceStart, key, keyOffset, keyLength, insertion, workspace);
    if (equality < 0) return StatusCode.INVARIANT_BROKEN;
    if (equality > 0) {
      return StatusCode.CONFLICT;
    }
    int total = workspace.header.entryCount() + 1;
    int splitAt = TupleBTreeLeafSplitPoint.choose(
        source, sourceStart, key, keyOffset, keyLength, insertion, workspace);
    if (splitAt < 0) return StatusCode.INVARIANT_BROKEN;
    if (splitAt == 0) return StatusCode.RESOURCE_EXHAUSTED;
    ByteBuffer separator;
    int separatorOffset;
    int separatorLength;
    if (splitAt == insertion) {
      separator = key;
      separatorOffset = keyOffset;
      separatorLength = keyLength;
    } else {
      if (!TupleBTreePageSupport.readLeaf(
          source, sourceStart, splitAt < insertion ? splitAt : splitAt - 1, workspace)) {
        return StatusCode.INVARIANT_BROKEN;
      }
      separator = source;
      separatorOffset = sourceStart + workspace.leaf.keyOffset();
      separatorLength = workspace.leaf.keyLength();
    }
    status = TupleBTreeLeafSplitOutput.initialize(
        source, sourceStart, left, leftStart, right, rightStart, leftPageId,
        rightPageId, schemaId, shape, separator, separatorOffset, separatorLength, workspace);
    if (!status.isOk()) return status;
    status = TupleBTreeLeafSplitOutput.appendMerged(
        source, sourceStart, left, leftStart, right, rightStart, shape,
        key, keyOffset, keyLength, insertion, splitAt, total, workspace);
    if (!status.isOk()) return status;
    status = TupleBTreeLeafSplitOutput.validate(
        left, leftStart, right, rightStart, schemaId, shape, workspace);
    if (!status.isOk()) return status;
    if (!TupleBTreePageSupport.readLeaf(right, rightStart, 0, workspace)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    result.set(
        right, rightStart + workspace.leaf.keyOffset(), workspace.leaf.keyLength(),
        splitAt, total - splitAt);
    return StatusCode.OK;
  }

}
