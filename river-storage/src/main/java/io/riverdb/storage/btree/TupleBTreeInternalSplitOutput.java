package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
import java.nio.ByteBuffer;

/** Materializes and validates the two unpublished pages produced by a split. */
final class TupleBTreeInternalSplitOutput {
  private TupleBTreeInternalSplitOutput() { }

  static StatusCode initialize(
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
    if (!status.isOk()) return status;
    return TupleBTreePageCodec.initialize(
        right, rightStart, TupleBTreePageCodec.TYPE_INTERNAL, rightFirstChild,
        shape, schemaId, highLength == 0 ? null : source,
        sourceStart + highOffset, highLength);
  }

  static StatusCode append(
      ByteBuffer source, int sourceStart, ByteBuffer target, int targetStart,
      TupleShape shape, ByteBuffer key, int keyOffset, int keyLength,
      int rightChild, int insertion, int index, TupleBTreeWorkspace workspace) {
    if (index == insertion) {
      return TupleBTreePageCodec.appendInternal(
          target, targetStart, shape, key, keyOffset, keyLength, rightChild);
    }
    return TupleBTreePageSupport.appendInternalSource(
        source, sourceStart, target, targetStart, shape,
        index < insertion ? index : index - 1, workspace);
  }

  static StatusCode appendMerged(
      ByteBuffer source, int sourceStart, ByteBuffer left, int leftStart,
      ByteBuffer right, int rightStart, TupleShape shape, ByteBuffer key,
      int keyOffset, int keyLength, int rightChild, int insertion, int promoted,
      int total, TupleBTreeWorkspace workspace) {
    for (int index = 0; index < total; index++) {
      if (index == promoted) continue;
      boolean toLeft = index < promoted;
      StatusCode status = append(
          source, sourceStart, toLeft ? left : right,
          toLeft ? leftStart : rightStart, shape, key, keyOffset, keyLength,
          rightChild, insertion, index, workspace);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  static StatusCode validate(
      ByteBuffer left, int leftStart, ByteBuffer right, int rightStart,
      long schemaId, TupleShape shape, TupleBTreeWorkspace workspace) {
    StatusCode status = TupleBTreePageCodec.validate(
        left, leftStart, schemaId, shape, workspace.header);
    if (!status.isOk()) return status;
    return TupleBTreePageCodec.validate(
        right, rightStart, schemaId, shape, workspace.header);
  }
}
