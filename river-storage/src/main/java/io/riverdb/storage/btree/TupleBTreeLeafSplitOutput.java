package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
import java.nio.ByteBuffer;

/** Materializes and validates the two unpublished leaves produced by a split. */
final class TupleBTreeLeafSplitOutput {
  private TupleBTreeLeafSplitOutput() { }

  static StatusCode initialize(
      ByteBuffer source, int sourceStart, ByteBuffer left, int leftStart,
      ByteBuffer right, int rightStart, int leftPageId, int rightPageId,
      long schemaId, TupleShape shape, ByteBuffer separator, int separatorOffset,
      int separatorLength, TupleBTreeWorkspace workspace) {
    int oldLeft = workspace.header.leftSiblingPageId();
    int oldRight = workspace.header.rightSiblingPageId();
    int highOffset = workspace.header.highKeyOffset();
    int highLength = workspace.header.highKeyLength();
    StatusCode status = TupleBTreePageCodec.initializeLeaf(
        left, leftStart, oldLeft, rightPageId,
        shape, schemaId, separator, separatorOffset, separatorLength);
    if (!status.isOk()) return status;
    return TupleBTreePageCodec.initializeLeaf(
        right, rightStart, leftPageId, oldRight,
        shape, schemaId, highLength == 0 ? null : source,
        sourceStart + highOffset, highLength);
  }

  static StatusCode append(
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

  static StatusCode appendMerged(
      ByteBuffer source, int sourceStart, ByteBuffer left, int leftStart,
      ByteBuffer right, int rightStart, TupleShape shape, ByteBuffer key,
      int keyOffset, int keyLength, int insertion, int splitAt, int total,
      TupleBTreeWorkspace workspace) {
    for (int index = 0; index < total; index++) {
      boolean toLeft = index < splitAt;
      StatusCode status = append(
          source, sourceStart, toLeft ? left : right,
          toLeft ? leftStart : rightStart, shape, key, keyOffset, keyLength,
          insertion, index, workspace);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  static StatusCode validate(
      ByteBuffer left, int leftStart, ByteBuffer right, int rightStart,
      long schemaId, TupleShape shape, TupleBTreeWorkspace workspace) {
    StatusCode status = TupleBTreePageAdmission.validate(
        left, leftStart, schemaId, shape, TupleBTreePageCodec.TYPE_LEAF, workspace);
    if (!status.isOk()) return status;
    return TupleBTreePageAdmission.validate(
        right, rightStart, schemaId, shape, TupleBTreePageCodec.TYPE_LEAF, workspace);
  }
}
