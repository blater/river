package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreePageCodec;
import java.nio.ByteBuffer;

/** One-time bound admission and initial leaf attachment for provider-backed cursors. */
final class TupleBTreeCursorOpen {
  private TupleBTreeCursorOpen() { }

  static StatusCode open(
      TupleBTreeCursor cursor, TupleBTree tree, TupleBTreeScanBounds bounds,
      TupleBTreeTreeWorkspace workspace) {
    StatusCode status = cursor.close();
    if (!status.isOk() || workspace == null || !TupleBTreeScanValidation.valid(tree, bounds)) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    copyBounds(cursor, bounds);
    cursor.rootPageId = tree.provider().rootPageId();
    cursor.rootGeneration = tree.provider().rootGeneration();
    status = descend(tree, cursor, workspace);
    if (status.isOk() && !sameRoot(cursor, tree)) status = StatusCode.RETRY;
    if (status.isOk()) status = attach(cursor, tree, workspace.leafPageId);
    if (!status.isOk()) {
      if (cursor.reference.isAttached()) return status;
      StatusCode closed = cursor.close();
      return closed.isOk() ? status : closed;
    }
    cursor.mode = TupleBTreeCursor.MODE_SCAN;
    return StatusCode.OK;
  }

  private static StatusCode descend(
      TupleBTree tree, TupleBTreeCursor cursor, TupleBTreeTreeWorkspace workspace) {
    if (cursor.direction == TupleBTreeScanBounds.FORWARD) {
      if (cursor.lowerShape == null) return TupleBTreeTraversal.leftmost(tree, workspace);
      return TupleBTreeTraversal.prefix(
          tree, cursor.lowerScratch, 0, cursor.lowerLength,
          cursor.lowerShape.partCount(), workspace);
    }
    if (cursor.upperShape == null) return TupleBTreeTraversal.rightmost(tree, workspace);
    ByteBuffer upper = cursor.upperUsesLower ? cursor.lowerScratch : cursor.upperScratch;
    return TupleBTreeTraversal.upperPrefix(
        tree, upper, 0, cursor.upperLength, cursor.upperShape.partCount(), workspace);
  }

  private static StatusCode attach(TupleBTreeCursor cursor, TupleBTree tree, int pageId) {
    StatusCode status = tree.provider().pin(pageId, false, cursor.reference);
    if (status.isOk()) cursor.tree = tree;
    if (status.isOk()) status = TupleBTreePageSupport.validate(
        cursor.reference.page(), cursor.reference.start(), tree.schemaId(), tree.shape(),
        TupleBTreePageCodec.TYPE_LEAF, cursor.workspace,
        tree.provider(), cursor.reference);
    if (status.isOk() && cursor.workspace.header.type() != TupleBTreePageCodec.TYPE_LEAF) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk() && !sameRoot(cursor, tree)) status = StatusCode.RETRY;
    if (!status.isOk()) return TupleBTreeProviderAccess.release(
        tree.provider(), cursor.reference, status);
    cursor.page = cursor.reference.page();
    cursor.pageStart = cursor.reference.start();
    cursor.header = cursor.workspace.header;
    cursor.limit = cursor.header.entryCount();
    cursor.index = cursor.direction == TupleBTreeScanBounds.FORWARD ? 0 : cursor.limit - 1;
    return StatusCode.OK;
  }

  private static void copyBounds(TupleBTreeCursor cursor, TupleBTreeScanBounds bounds) {
    cursor.lowerLength = copy(bounds.lower, bounds.lowerOffset, bounds.lowerLength,
        cursor.lowerScratch);
    cursor.lowerShape = bounds.lowerShape;
    cursor.lowerInclusive = bounds.lowerInclusive;
    cursor.upperUsesLower = bounds.lower != null && bounds.lower == bounds.upper
        && bounds.lowerOffset == bounds.upperOffset && bounds.lowerLength == bounds.upperLength;
    cursor.upperLength = cursor.upperUsesLower ? cursor.lowerLength
        : copy(bounds.upper, bounds.upperOffset, bounds.upperLength, cursor.upperScratch);
    cursor.upperShape = bounds.upperShape;
    cursor.upperInclusive = bounds.upperInclusive;
    cursor.direction = bounds.direction;
  }

  private static int copy(ByteBuffer source, int offset, int length, ByteBuffer target) {
    if (source == null) return 0;
    for (int index = 0; index < length; index++) target.put(index, source.get(offset + index));
    return length;
  }

  static boolean sameRoot(TupleBTreeCursor cursor, TupleBTree tree) {
    return tree.provider().rootPageId() == cursor.rootPageId
        && tree.provider().rootGeneration() == cursor.rootGeneration;
  }

  static void clear(ByteBuffer target, int length) {
    for (int index = 0; index < length; index++) target.put(index, (byte) 0);
  }
}
