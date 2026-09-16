package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import java.nio.ByteBuffer;

/** Unpublished two-page internal split with a borrowed promoted separator. */
final class TupleBTreeInternalSplit {
  private TupleBTreeInternalSplit() { }

  static StatusCode splitInsert(
      ByteBuffer source, int sourceStart,
      ByteBuffer left, int leftStart,
      ByteBuffer right, int rightStart,
      long schemaId, TupleShape shape,
      ByteBuffer separator, int separatorOffset, int separatorLength,
      int rightChildPageId, TupleBTreeWorkspace workspace,
      TupleBTreeSplitResult result) {
    StatusCode status = TupleBTreeInternalSplitAdmission.prepare(
        source, sourceStart, left, leftStart, right, rightStart,
        schemaId, shape, separator, separatorOffset, separatorLength,
        rightChildPageId, workspace, result);
    if (!status.isOk()) return status;
    int insertion = TupleBTreePageSupport.lowerBoundInternal(
        source, sourceStart, separator, separatorOffset, separatorLength, workspace);
    if (TupleBTreeInternalSplitAdmission.equalAt(
        source, sourceStart, separator, separatorOffset, separatorLength,
        insertion, workspace)) return StatusCode.CONFLICT;
    int total = workspace.header.entryCount() + 1;
    int promoted = TupleBTreeInternalSplitPoint.choose(
        source, sourceStart, separatorLength, insertion, workspace);
    if (promoted < 0) return StatusCode.RESOURCE_EXHAUSTED;
    ByteBuffer promotedSource;
    int promotedOffset;
    int promotedLength;
    int promotedChild;
    if (promoted == insertion) {
      promotedSource = separator;
      promotedOffset = separatorOffset;
      promotedLength = separatorLength;
      promotedChild = rightChildPageId;
    } else {
      TupleBTreePageSupport.readInternal(
          source, sourceStart, promoted < insertion ? promoted : promoted - 1, workspace);
      promotedSource = source;
      promotedOffset = sourceStart + workspace.internal.keyOffset();
      promotedLength = workspace.internal.keyLength();
      promotedChild = workspace.internal.rightChildPageId();
    }
    status = TupleBTreeInternalSplitOutput.initialize(
        source, sourceStart, left, leftStart, right, rightStart,
        schemaId, shape, promotedSource, promotedOffset, promotedLength,
        promotedChild, workspace);
    if (!status.isOk()) return status;
    status = TupleBTreeInternalSplitOutput.appendMerged(
        source, sourceStart, left, leftStart, right, rightStart, shape,
        separator, separatorOffset, separatorLength, rightChildPageId,
        insertion, promoted, total, workspace);
    if (!status.isOk()) return status;
    status = TupleBTreeInternalSplitOutput.validate(
        left, leftStart, right, rightStart, schemaId, shape, workspace);
    if (!status.isOk()) return status;
    result.set(promotedSource, promotedOffset, promotedLength,
        promoted, total - promoted - 1);
    return StatusCode.OK;
  }

}
