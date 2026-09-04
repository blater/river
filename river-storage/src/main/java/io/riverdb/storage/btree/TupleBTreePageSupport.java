package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Shared low-level operations over already validated tuple pages. */
final class TupleBTreePageSupport {
  private TupleBTreePageSupport() { }

  static int lowerBoundLeaf(
      ByteBuffer page, int start, ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace) {
    int low = 0;
    int high = workspace.header.entryCount();
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (!readLeaf(page, start, middle, workspace)) return -1;
      int comparison = TupleKeyCodec.compare(
          page, start + workspace.leaf.keyOffset(), workspace.leaf.keyLength(),
          key, keyOffset, keyLength);
      if (comparison < 0) low = middle + 1;
      else high = middle;
    }
    return low;
  }

  static int lowerBoundInternal(
      ByteBuffer page, int start, ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace) {
    int low = 0;
    int high = workspace.header.entryCount();
    while (low < high) {
      int middle = (low + high) >>> 1;
      readInternal(page, start, middle, workspace);
      int comparison = TupleKeyCodec.compare(
          page, start + workspace.internal.keyOffset(), workspace.internal.keyLength(),
          key, keyOffset, keyLength);
      if (comparison < 0) low = middle + 1;
      else high = middle;
    }
    return low;
  }

  static StatusCode initializeLike(
      ByteBuffer source, int sourceStart, ByteBuffer target, int targetStart,
      long schemaId, TupleShape shape, int type, TupleBTreeWorkspace workspace) {
    int highLength = workspace.header.highKeyLength();
    if (type == TupleBTreePageCodec.TYPE_LEAF) return TupleBTreePageCodec.initializeLeaf(
        target, targetStart, workspace.header.leftSiblingPageId(),
        workspace.header.rightSiblingPageId(), shape, schemaId,
        highLength == 0 ? null : source,
        sourceStart + workspace.header.highKeyOffset(), highLength);
    return TupleBTreePageCodec.initialize(
        target, targetStart, type, workspace.header.firstChildPageId(), shape, schemaId,
        highLength == 0 ? null : source,
        sourceStart + workspace.header.highKeyOffset(), highLength);
  }

  static StatusCode appendLeafSource(
      ByteBuffer source, int sourceStart, ByteBuffer target, int targetStart,
      TupleShape shape, int sourceIndex, TupleBTreeWorkspace workspace) {
    if (!readLeaf(source, sourceStart, sourceIndex, workspace)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    return TupleBTreePageCodec.appendLeaf(
        target, targetStart, shape, source,
        sourceStart + workspace.leaf.keyOffset(), workspace.leaf.keyLength());
  }

  static StatusCode appendInternalSource(
      ByteBuffer source, int sourceStart, ByteBuffer target, int targetStart,
      TupleShape shape, int sourceIndex, TupleBTreeWorkspace workspace) {
    readInternal(source, sourceStart, sourceIndex, workspace);
    return TupleBTreePageCodec.appendInternal(
        target, targetStart, shape, source,
        sourceStart + workspace.internal.keyOffset(), workspace.internal.keyLength(),
        workspace.internal.rightChildPageId());
  }

  static boolean readLeaf(
      ByteBuffer page, int start, int index, TupleBTreeWorkspace workspace) {
    return TupleBTreePageCodec.readValidatedLeaf(
        page, start, workspace.header, index, workspace.leaf).isOk();
  }

  static void readInternal(
      ByteBuffer page, int start, int index, TupleBTreeWorkspace workspace) {
    TupleBTreePageCodec.readInternal(page, start, workspace.header, index, workspace.internal);
  }

  static boolean validMutationBuffers(
      ByteBuffer page, ByteBuffer scratch, int start, int scratchStart) {
    return page != scratch && validPayload(page, start, true)
        && validPayload(scratch, scratchStart, true);
  }

  static boolean validPayload(ByteBuffer page, int start, boolean writable) {
    return page != null && (!writable || !page.isReadOnly()) && start >= 0
        && page.limit() - start >= PageCodec.MAX_PAYLOAD_BYTES;
  }

  static void copyPayload(
      ByteBuffer source, int sourceStart, ByteBuffer target, int targetStart) {
    for (int index = 0; index < PageCodec.MAX_PAYLOAD_BYTES; index++) {
      target.put(targetStart + index, source.get(sourceStart + index));
    }
  }
}
