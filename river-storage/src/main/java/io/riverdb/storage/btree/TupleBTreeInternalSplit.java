package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
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
    if (promoted != insertion) {
      TupleBTreePageSupport.readInternal(
          source, sourceStart, promoted < insertion ? promoted : promoted - 1, workspace);
    }
    ByteBuffer promotedSource = promoted == insertion ? separator : source;
    int promotedOffset = promoted == insertion
        ? separatorOffset : sourceStart + workspace.internal.keyOffset();
    int promotedLength = promoted == insertion
        ? separatorLength : workspace.internal.keyLength();
    int promotedChild = promoted == insertion
        ? rightChildPageId : workspace.internal.rightChildPageId();
    status = initializeOutputs(
        source, sourceStart, left, leftStart, right, rightStart,
        schemaId, shape, promotedSource, promotedOffset, promotedLength,
        promotedChild, workspace);
    if (!status.isOk()) return status;
    for (int index = 0; index < total; index++) {
      if (index == promoted) continue;
      status = appendMerged(
          source, sourceStart, index < promoted ? left : right,
          index < promoted ? leftStart : rightStart, shape,
          separator, separatorOffset, separatorLength, rightChildPageId,
          insertion, index, workspace);
      if (!status.isOk()) return status;
    }
    status = validateOutputs(left, leftStart, right, rightStart, schemaId, shape, workspace);
    if (!status.isOk()) return status;
    result.set(promotedSource, promotedOffset, promotedLength,
        promoted, total - promoted - 1);
    return StatusCode.OK;
  }

  private static StatusCode initializeOutputs(
      ByteBuffer source, int sourceStart, ByteBuffer left, int leftStart,
      ByteBuffer right, int rightStart, long schemaId, TupleShape shape,
      ByteBuffer promoted, int promotedOffset, int promotedLength,
      int rightFirstChild, TupleBTreeWorkspace workspace) {
    int oldFirstChild = workspace.header.firstChildPageId();
    int highOffset = workspace.header.highKeyOffset();
    int highLength = workspace.header.highKeyLength();
    StatusCode status = TupleBTreePageCodec.initialize(
        left, leftStart, TupleBTreePageCodec.TYPE_INTERNAL, oldFirstChild,
        shape, schemaId, promoted, promotedOffset, promotedLength);
    return status.isOk() ? TupleBTreePageCodec.initialize(
        right, rightStart, TupleBTreePageCodec.TYPE_INTERNAL, rightFirstChild,
        shape, schemaId, highLength == 0 ? null : source,
        sourceStart + highOffset, highLength) : status;
  }

  private static StatusCode appendMerged(
      ByteBuffer source, int sourceStart, ByteBuffer target, int targetStart,
      TupleShape shape, ByteBuffer key, int keyOffset, int keyLength, int rightChild,
      int insertion, int index, TupleBTreeWorkspace workspace) {
    if (index == insertion) {
      return TupleBTreePageCodec.appendInternal(
          target, targetStart, shape, key, keyOffset, keyLength, rightChild);
    }
    return TupleBTreePageSupport.appendInternalSource(
        source, sourceStart, target, targetStart, shape,
        index < insertion ? index : index - 1, workspace);
  }

  private static StatusCode validateOutputs(
      ByteBuffer left, int leftStart, ByteBuffer right, int rightStart,
      long schemaId, TupleShape shape, TupleBTreeWorkspace workspace) {
    StatusCode status = TupleBTreePageCodec.validate(
        left, leftStart, schemaId, shape, workspace.header);
    return status.isOk() ? TupleBTreePageCodec.validate(
        right, rightStart, schemaId, shape, workspace.header) : status;
  }
}
