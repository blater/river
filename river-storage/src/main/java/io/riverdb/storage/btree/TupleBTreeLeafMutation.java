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
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace) {
    return insert(
        page, start, schemaId, shape, key, keyOffset, keyLength,
        workspace, null, null);
  }

  static StatusCode insert(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace,
      TupleBTreePageProvider provider, TupleBTreePageReference reference) {
    return insert(page, start, schemaId, shape, key, keyOffset, keyLength,
        null, 0, 0, 0, 0, 0, workspace, provider, reference);
  }

  static StatusCode insert(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      ByteBuffer value, int valueOffset, int valueLength,
      int overflowPageId, long overflowGeneration, long modificationSequence,
      TupleBTreeWorkspace workspace,
      TupleBTreePageProvider provider, TupleBTreePageReference reference) {
    StatusCode status = TupleBTreeLeafMutationPreparation.prepare(
        page, start, schemaId, shape, key, keyOffset, keyLength,
        workspace, provider, reference);
    if (!status.isOk()) return status;
    int insertion = TupleBTreePageSupport.lowerBoundLeaf(
        page, start, key, keyOffset, keyLength, workspace);
    if (insertion < 0) {
      workspace.mutation.reset();
      return StatusCode.INVARIANT_BROKEN;
    }
    int equality = equalAt(
        page, start, key, keyOffset, keyLength, insertion, workspace);
    if (equality < 0) {
      workspace.mutation.reset();
      return StatusCode.INVARIANT_BROKEN;
    }
    if (equality > 0) {
      workspace.mutation.reset();
      return StatusCode.CONFLICT;
    }
    return TupleBTreePageCodec.insertPreparedLeaf(
        page, start, schemaId, shape,
        key, keyOffset, keyLength, value, valueOffset, valueLength,
        overflowPageId, overflowGeneration, modificationSequence,
        insertion, workspace.mutation);
  }

  static StatusCode delete(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace) {
    return delete(
        page, start, schemaId, shape, key, keyOffset, keyLength,
        workspace, null, null);
  }

  static StatusCode replace(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      ByteBuffer value, int valueOffset, int valueLength,
      int overflowPageId, long overflowGeneration, long modificationSequence,
      TupleBTreeWorkspace workspace,
      TupleBTreePageProvider provider, TupleBTreePageReference reference) {
    StatusCode status = TupleBTreeLeafMutationPreparation.prepare(
        page, start, schemaId, shape, key, keyOffset, keyLength,
        workspace, provider, reference);
    if (!status.isOk()) return status;
    int index = TupleBTreePageSupport.lowerBoundLeaf(
        page, start, key, keyOffset, keyLength, workspace);
    if (index < 0) {
      workspace.mutation.reset();
      return StatusCode.INVARIANT_BROKEN;
    }
    int equality = equalAt(page, start, key, keyOffset, keyLength, index, workspace);
    if (equality <= 0) {
      workspace.mutation.reset();
      return equality < 0 ? StatusCode.INVARIANT_BROKEN : StatusCode.CONFLICT;
    }
    int removedPageId = workspace.leaf.overflowPageId();
    long removedGeneration = workspace.leaf.overflowGeneration();
    status = TupleBTreePageCodec.replacePreparedLeafValue(
        page, start, schemaId, shape, key, keyOffset, keyLength,
        value, valueOffset, valueLength,
        overflowPageId, overflowGeneration, modificationSequence,
        index, workspace.mutation);
    if (status.isOk()) workspace.removedValue(removedPageId, removedGeneration);
    return status;
  }

  static StatusCode delete(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace,
      TupleBTreePageProvider provider, TupleBTreePageReference reference) {
    StatusCode status = TupleBTreeLeafMutationPreparation.prepare(
        page, start, schemaId, shape, key, keyOffset, keyLength,
        workspace, provider, reference);
    if (!status.isOk()) return status;
    int deletion = TupleBTreePageSupport.lowerBoundLeaf(
        page, start, key, keyOffset, keyLength, workspace);
    if (deletion < 0) {
      workspace.mutation.reset();
      return StatusCode.INVARIANT_BROKEN;
    }
    int equality = equalAt(
        page, start, key, keyOffset, keyLength, deletion, workspace);
    if (equality < 0) {
      workspace.mutation.reset();
      return StatusCode.INVARIANT_BROKEN;
    }
    if (equality == 0) {
      workspace.mutation.reset();
      return StatusCode.CONFLICT;
    }
    int removedPageId = workspace.leaf.overflowPageId();
    long removedGeneration = workspace.leaf.overflowGeneration();
    status = TupleBTreePageCodec.deletePreparedLeaf(
        page, start, schemaId, shape, deletion, workspace.mutation);
    if (status.isOk()) workspace.removedValue(removedPageId, removedGeneration);
    return status;
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

}
