package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Transactional caller-scratch insert and delete for one tuple leaf. */
final class TupleBTreeLeafMutation {
  private TupleBTreeLeafMutation() { }

  static StatusCode insert(
      ByteBuffer page, int start, ByteBuffer scratch, int scratchStart,
      long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace) {
    StatusCode status = prepare(
        page, start, scratch, scratchStart, schemaId, shape,
        key, keyOffset, keyLength, workspace);
    if (!status.isOk()) return status;
    int insertion = TupleBTreePageSupport.lowerBoundLeaf(
        page, start, key, keyOffset, keyLength, workspace);
    if (equalAt(page, start, key, keyOffset, keyLength, insertion, workspace)) {
      return StatusCode.CONFLICT;
    }
    int count = workspace.header.entryCount();
    status = TupleBTreePageSupport.initializeLike(
        page, start, scratch, scratchStart, schemaId, shape,
        TupleBTreePageCodec.TYPE_LEAF, workspace);
    if (!status.isOk()) return status;
    for (int target = 0; target <= count; target++) {
      status = target == insertion
          ? TupleBTreePageCodec.appendLeaf(
              scratch, scratchStart, shape, key, keyOffset, keyLength)
          : TupleBTreePageSupport.appendLeafSource(
              page, start, scratch, scratchStart, shape,
              target < insertion ? target : target - 1, workspace);
      if (!status.isOk()) return status;
    }
    return publish(page, start, scratch, scratchStart, schemaId, shape, workspace);
  }

  static StatusCode delete(
      ByteBuffer page, int start, ByteBuffer scratch, int scratchStart,
      long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace) {
    StatusCode status = prepare(
        page, start, scratch, scratchStart, schemaId, shape,
        key, keyOffset, keyLength, workspace);
    if (!status.isOk()) return status;
    int deletion = TupleBTreePageSupport.lowerBoundLeaf(
        page, start, key, keyOffset, keyLength, workspace);
    if (!equalAt(page, start, key, keyOffset, keyLength, deletion, workspace)) {
      return StatusCode.CONFLICT;
    }
    int count = workspace.header.entryCount();
    status = TupleBTreePageSupport.initializeLike(
        page, start, scratch, scratchStart, schemaId, shape,
        TupleBTreePageCodec.TYPE_LEAF, workspace);
    if (!status.isOk()) return status;
    for (int sourceIndex = 0; sourceIndex < count; sourceIndex++) {
      if (sourceIndex == deletion) continue;
      status = TupleBTreePageSupport.appendLeafSource(
          page, start, scratch, scratchStart, shape, sourceIndex, workspace);
      if (!status.isOk()) return status;
    }
    return publish(page, start, scratch, scratchStart, schemaId, shape, workspace);
  }

  private static StatusCode prepare(
      ByteBuffer page, int start, ByteBuffer scratch, int scratchStart,
      long schemaId, TupleShape shape, ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace) {
    if (workspace == null || !TupleBTreePageSupport.validMutationBuffers(
        page, scratch, start, scratchStart)) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = TupleBTreePageSupport.validate(
        page, start, schemaId, shape, TupleBTreePageCodec.TYPE_LEAF, workspace);
    if (!status.isOk()) return status;
    return TupleKeyCodec.matchesPhysicalIndexKey(key, keyOffset, keyLength, shape)
        ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static boolean equalAt(
      ByteBuffer page, int start, ByteBuffer key, int keyOffset, int keyLength,
      int index, TupleBTreeWorkspace workspace) {
    if (index >= workspace.header.entryCount()) return false;
    TupleBTreePageSupport.readLeaf(page, start, index, workspace);
    return TupleKeyCodec.compare(
        page, start + workspace.leaf.keyOffset(), workspace.leaf.keyLength(),
        key, keyOffset, keyLength) == 0;
  }

  private static StatusCode publish(
      ByteBuffer page, int start, ByteBuffer scratch, int scratchStart,
      long schemaId, TupleShape shape, TupleBTreeWorkspace workspace) {
    StatusCode status = TupleBTreePageCodec.validate(
        scratch, scratchStart, schemaId, shape, workspace.header);
    if (!status.isOk()) return status;
    TupleBTreePageSupport.copyPayload(scratch, scratchStart, page, start);
    return StatusCode.OK;
  }
}
