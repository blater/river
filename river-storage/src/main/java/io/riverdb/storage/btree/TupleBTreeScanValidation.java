package io.riverdb.storage.btree;

import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleKeyCodec;

/** Tree-relative validation for borrowed user-tuple scan bounds. */
final class TupleBTreeScanValidation {
  private TupleBTreeScanValidation() { }

  static boolean valid(TupleBTree tree, TupleBTreeScanBounds bounds) {
    if (tree == null || bounds == null || bounds.kind == 0
        || (bounds.direction != TupleBTreeScanBounds.FORWARD
        && bounds.direction != TupleBTreeScanBounds.REVERSE)) return false;
    if (!side(tree, bounds.lower, bounds.lowerOffset, bounds.lowerLength, bounds.lowerShape)
        || !side(tree, bounds.upper, bounds.upperOffset, bounds.upperLength,
        bounds.upperShape)) return false;
    if (bounds.kind == TupleBTreeScanBounds.ALL) {
      return bounds.lower == null && bounds.upper == null;
    }
    if (bounds.lower == null && bounds.upper == null) return false;
    if (bounds.kind == TupleBTreeScanBounds.EXACT) {
      return same(bounds) && bounds.lowerShape.partCount() == tree.shape().partCount();
    }
    if (bounds.kind == TupleBTreeScanBounds.PREFIX) return same(bounds);
    return ordered(bounds);
  }

  private static boolean side(
      TupleBTree tree, java.nio.ByteBuffer key, int offset, int length, TupleShape shape) {
    if (key == null) return shape == null;
    if (length > TupleKeyCodec.MAX_INDEX_USER_KEY_BYTES || TupleKeyCodec.isPhysical(
        key, offset, length) || !TupleKeyCodec.matchesShape(key, offset, length, shape)
        || shape.partCount() > tree.shape().partCount()) return false;
    for (int part = 0; part < shape.partCount(); part++) {
      if (shape.descriptorAt(part) != tree.shape().descriptorAt(part)) return false;
    }
    return true;
  }

  private static boolean same(TupleBTreeScanBounds bounds) {
    return bounds.lower == bounds.upper && bounds.lowerOffset == bounds.upperOffset
        && bounds.lowerLength == bounds.upperLength && bounds.lowerShape == bounds.upperShape;
  }

  private static boolean ordered(TupleBTreeScanBounds bounds) {
    if (bounds.lower == null || bounds.upper == null) return true;
    int parts = Math.min(bounds.lowerShape.partCount(), bounds.upperShape.partCount());
    return TupleKeyCodec.comparePrefix(
        bounds.lower, bounds.lowerOffset, bounds.lowerLength,
        bounds.upper, bounds.upperOffset, bounds.upperLength, parts) <= 0;
  }
}
