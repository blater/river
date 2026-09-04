package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyCodec;
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
    StatusCode status = prepare(
        source, sourceStart, left, leftStart, right, rightStart,
        leftPageId, rightPageId, schemaId, shape,
        key, keyOffset, keyLength, workspace, result);
    if (!status.isOk()) return status;
    int insertion = TupleBTreePageSupport.lowerBoundLeaf(
        source, sourceStart, key, keyOffset, keyLength, workspace);
    if (insertion < 0) return StatusCode.INVARIANT_BROKEN;
    int equality = equalAt(
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
    if (splitAt != insertion) {
      if (!TupleBTreePageSupport.readLeaf(
          source, sourceStart, splitAt < insertion ? splitAt : splitAt - 1, workspace)) {
        return StatusCode.INVARIANT_BROKEN;
      }
    }
    ByteBuffer separator = splitAt == insertion ? key : source;
    int separatorOffset = splitAt == insertion
        ? keyOffset : sourceStart + workspace.leaf.keyOffset();
    int separatorLength = splitAt == insertion ? keyLength : workspace.leaf.keyLength();
    status = initializeOutputs(
        source, sourceStart, left, leftStart, right, rightStart, leftPageId,
        rightPageId, schemaId, shape, separator, separatorOffset, separatorLength, workspace);
    if (!status.isOk()) return status;
    for (int index = 0; index < total; index++) {
      status = appendMerged(
          source, sourceStart, index < splitAt ? left : right,
          index < splitAt ? leftStart : rightStart, shape,
          key, keyOffset, keyLength, insertion, index, workspace);
      if (!status.isOk()) return status;
    }
    status = validateOutputs(left, leftStart, right, rightStart, schemaId, shape, workspace);
    if (!status.isOk()) return status;
    if (!TupleBTreePageSupport.readLeaf(right, rightStart, 0, workspace)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    result.set(
        right, rightStart + workspace.leaf.keyOffset(), workspace.leaf.keyLength(),
        splitAt, total - splitAt);
    return StatusCode.OK;
  }

  private static StatusCode prepare(
      ByteBuffer source, int sourceStart, ByteBuffer left, int leftStart,
      ByteBuffer right, int rightStart, int leftPageId,
      int rightPageId, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace, TupleBTreeSplitResult result) {
    if (workspace == null || result == null || source == left || source == right || left == right
        || key == left || key == right || leftPageId <= 0 || rightPageId <= 0
        || leftPageId == rightPageId
        || !TupleBTreePageSupport.validPayload(left, leftStart, true)
        || !TupleBTreePageSupport.validPayload(right, rightStart, true)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = TupleBTreePageAdmission.validate(
        source, sourceStart, schemaId, shape, TupleBTreePageCodec.TYPE_LEAF, workspace);
    if (!status.isOk()) return status;
    return TupleKeyCodec.matchesPhysicalIndexKey(key, keyOffset, keyLength, shape)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static int equalAt(
      ByteBuffer page, int start, ByteBuffer key, int keyOffset, int keyLength,
      int index, TupleBTreeWorkspace workspace) {
    if (index >= workspace.header.entryCount()) return 0;
    if (!TupleBTreePageSupport.readLeaf(page, start, index, workspace)) return -1;
    return TupleKeyCodec.compare(
        page, start + workspace.leaf.keyOffset(), workspace.leaf.keyLength(),
        key, keyOffset, keyLength) == 0 ? 1 : 0;
  }

  private static StatusCode initializeOutputs(
      ByteBuffer source, int sourceStart, ByteBuffer left, int leftStart,
      ByteBuffer right, int rightStart, int leftPageId,
      int rightPageId, long schemaId, TupleShape shape, ByteBuffer separator,
      int separatorOffset, int separatorLength, TupleBTreeWorkspace workspace) {
    int oldLeft = workspace.header.leftSiblingPageId();
    int oldRight = workspace.header.rightSiblingPageId();
    int highOffset = workspace.header.highKeyOffset();
    int highLength = workspace.header.highKeyLength();
    StatusCode status = TupleBTreePageCodec.initializeLeaf(
        left, leftStart, oldLeft, rightPageId,
        shape, schemaId, separator, separatorOffset, separatorLength);
    return status.isOk() ? TupleBTreePageCodec.initializeLeaf(
        right, rightStart, leftPageId, oldRight,
        shape, schemaId, highLength == 0 ? null : source,
        sourceStart + highOffset, highLength) : status;
  }

  private static StatusCode appendMerged(
      ByteBuffer source, int sourceStart, ByteBuffer target, int targetStart,
      TupleShape shape, ByteBuffer key, int keyOffset, int keyLength,
      int insertion, int index, TupleBTreeWorkspace workspace) {
    if (index == insertion) {
      return TupleBTreePageCodec.appendLeaf(
          target, targetStart, shape, key, keyOffset, keyLength);
    }
    return TupleBTreePageSupport.appendLeafSource(
        source, sourceStart, target, targetStart, shape,
        index < insertion ? index : index - 1, workspace);
  }

  private static StatusCode validateOutputs(
      ByteBuffer left, int leftStart, ByteBuffer right, int rightStart,
      long schemaId, TupleShape shape, TupleBTreeWorkspace workspace) {
    StatusCode status = TupleBTreePageAdmission.validate(
        left, leftStart, schemaId, shape, TupleBTreePageCodec.TYPE_LEAF, workspace);
    return status.isOk() ? TupleBTreePageAdmission.validate(
        right, rightStart, schemaId, shape, TupleBTreePageCodec.TYPE_LEAF, workspace) : status;
  }
}
