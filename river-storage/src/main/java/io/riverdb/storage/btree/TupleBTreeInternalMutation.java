package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Transactional caller-scratch separator insertion into one internal page. */
final class TupleBTreeInternalMutation {
  private TupleBTreeInternalMutation() { }

  static StatusCode insert(
      ByteBuffer page, int start, ByteBuffer scratch, int scratchStart,
      long schemaId, TupleShape shape,
      ByteBuffer separator, int separatorOffset, int separatorLength,
      int rightChildPageId, TupleBTreeWorkspace workspace) {
    StatusCode status = prepare(
        page, start, scratch, scratchStart, schemaId, shape,
        separator, separatorOffset, separatorLength, rightChildPageId, workspace);
    if (!status.isOk()) return status;
    int insertion = TupleBTreePageSupport.lowerBoundInternal(
        page, start, separator, separatorOffset, separatorLength, workspace);
    if (equalAt(page, start, separator, separatorOffset, separatorLength, insertion, workspace)) {
      return StatusCode.CONFLICT;
    }
    int count = workspace.header.entryCount();
    status = TupleBTreePageSupport.initializeLike(
        page, start, scratch, scratchStart, schemaId, shape,
        TupleBTreePageCodec.TYPE_INTERNAL, workspace);
    if (!status.isOk()) return status;
    for (int target = 0; target <= count; target++) {
      status = target == insertion
          ? TupleBTreePageCodec.appendInternal(
              scratch, scratchStart, shape, separator, separatorOffset,
              separatorLength, rightChildPageId)
          : TupleBTreePageSupport.appendInternalSource(
              page, start, scratch, scratchStart, shape,
              target < insertion ? target : target - 1, workspace);
      if (!status.isOk()) return status;
    }
    status = TupleBTreePageCodec.validate(
        scratch, scratchStart, schemaId, shape, workspace.header);
    if (!status.isOk()) return status;
    TupleBTreePageSupport.copyPayload(scratch, scratchStart, page, start);
    return StatusCode.OK;
  }

  private static StatusCode prepare(
      ByteBuffer page, int start, ByteBuffer scratch, int scratchStart,
      long schemaId, TupleShape shape, ByteBuffer key, int keyOffset, int keyLength,
      int rightChild, TupleBTreeWorkspace workspace) {
    if (workspace == null || rightChild <= 0
        || !TupleBTreePageSupport.validMutationBuffers(page, scratch, start, scratchStart)
        || !TupleKeyCodec.matchesPhysicalIndexKey(key, keyOffset, keyLength, shape)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return TupleBTreePageAdmission.validate(
        page, start, schemaId, shape, TupleBTreePageCodec.TYPE_INTERNAL, workspace);
  }

  private static boolean equalAt(
      ByteBuffer page, int start, ByteBuffer key, int keyOffset, int keyLength,
      int index, TupleBTreeWorkspace workspace) {
    if (index >= workspace.header.entryCount()) return false;
    TupleBTreePageSupport.readInternal(page, start, index, workspace);
    return TupleKeyCodec.compare(
        page, start + workspace.internal.keyOffset(), workspace.internal.keyLength(),
        key, keyOffset, keyLength) == 0;
  }
}
