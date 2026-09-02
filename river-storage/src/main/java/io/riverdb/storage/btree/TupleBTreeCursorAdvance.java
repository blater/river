package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreeLeafEntry;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Allocation-free bidirectional advancement with inclusive composite bound enforcement. */
final class TupleBTreeCursorAdvance {
  private TupleBTreeCursorAdvance() { }

  static StatusCode next(TupleBTreeCursor cursor, TupleBTreeLeafEntry result) {
    result.reset();
    if (cursor.mode != TupleBTreeCursor.MODE_SCAN || cursor.tree == null
        || !cursor.reference.isAttached()) return StatusCode.INVALID_EXTERNAL_INPUT;
    while (true) {
      while (available(cursor)) {
        int entryIndex = cursor.index;
        cursor.index += cursor.direction;
        StatusCode status = TupleBTreePageCodec.readLeaf(
            cursor.page, cursor.pageStart, cursor.header, entryIndex, result);
        if (!status.isOk()) return finish(cursor, result, status);
        int location = location(cursor, result);
        if (location == 0) return StatusCode.OK;
        if (location == cursor.direction) return finish(cursor, result, StatusCode.CONFLICT);
      }
      StatusCode status = transition(cursor);
      if (!status.isOk()) return cursor.transitionReference.isAttached()
          ? status : finish(cursor, result, status);
    }
  }

  private static boolean available(TupleBTreeCursor cursor) {
    return cursor.direction == TupleBTreeScanBounds.FORWARD
        ? cursor.index < cursor.limit : cursor.index >= 0;
  }

  /** Returns -1 below the range, zero inside, and 1 above. */
  private static int location(TupleBTreeCursor cursor, TupleBTreeLeafEntry entry) {
    int offset = cursor.pageStart + entry.keyOffset();
    if (cursor.lowerShape != null) {
      int compared = TupleKeyCodec.comparePrefix(
          cursor.page, offset, entry.keyLength(), cursor.lowerScratch, 0,
          cursor.lowerLength, cursor.lowerShape.partCount());
      if (compared < 0 || compared == 0 && !cursor.lowerInclusive) return -1;
    }
    if (cursor.upperShape != null) {
      ByteBuffer upper = cursor.upperUsesLower ? cursor.lowerScratch : cursor.upperScratch;
      int compared = TupleKeyCodec.comparePrefix(
          cursor.page, offset, entry.keyLength(), upper, 0,
          cursor.upperLength, cursor.upperShape.partCount());
      if (compared > 0 || compared == 0 && !cursor.upperInclusive) return 1;
    }
    return 0;
  }

  private static StatusCode transition(TupleBTreeCursor cursor) {
    if (!TupleBTreeCursorOpen.sameRoot(cursor, cursor.tree)) return StatusCode.RETRY;
    int current = cursor.reference.pageId();
    int adjacent = cursor.direction == TupleBTreeScanBounds.FORWARD
        ? cursor.header.rightSiblingPageId() : cursor.header.leftSiblingPageId();
    if (adjacent == 0) return StatusCode.CONFLICT;
    if (adjacent == current) return StatusCode.CORRUPTION;
    StatusCode status = cursor.tree.provider().pin(
        adjacent, false, cursor.transitionReference);
    if (status.isOk()) status = validateAdjacent(cursor, current);
    if (!status.isOk()) return TupleBTreeProviderAccess.release(
        cursor.tree.provider(), cursor.transitionReference, status);
    status = TupleBTreeProviderAccess.release(
        cursor.tree.provider(), cursor.reference, StatusCode.OK);
    if (!status.isOk()) return status;
    TupleBTreePageReference released = cursor.reference;
    cursor.reference = cursor.transitionReference;
    cursor.transitionReference = released;
    cursor.page = cursor.reference.page();
    cursor.pageStart = cursor.reference.start();
    cursor.header = nextHeader(cursor);
    cursor.limit = cursor.header.entryCount();
    cursor.index = cursor.direction == TupleBTreeScanBounds.FORWARD ? 0 : cursor.limit - 1;
    return StatusCode.OK;
  }

  private static StatusCode validateAdjacent(TupleBTreeCursor cursor, int current) {
    io.riverdb.format.btree.TupleBTreePageHeader header = nextHeader(cursor);
    StatusCode status = TupleBTreePageSupport.validate(
        cursor.transitionReference.page(), cursor.transitionReference.start(),
        cursor.tree.schemaId(), cursor.tree.shape(), TupleBTreePageCodec.TYPE_LEAF,
        header, cursor.tree.provider(), cursor.transitionReference);
    if (!status.isOk()) return status;
    if (header.type() != TupleBTreePageCodec.TYPE_LEAF) return StatusCode.CORRUPTION;
    if (!TupleBTreeCursorOpen.sameRoot(cursor, cursor.tree)) return StatusCode.RETRY;
    int reciprocal = cursor.direction == TupleBTreeScanBounds.FORWARD
        ? header.leftSiblingPageId() : header.rightSiblingPageId();
    return reciprocal == current ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private static io.riverdb.format.btree.TupleBTreePageHeader nextHeader(
      TupleBTreeCursor cursor) {
    return cursor.header == cursor.workspace.header
        ? cursor.transitionHeader : cursor.workspace.header;
  }

  private static StatusCode finish(
      TupleBTreeCursor cursor, TupleBTreeLeafEntry result, StatusCode status) {
    result.reset();
    StatusCode closed = cursor.close();
    return closed.isOk() ? status : closed;
  }
}
