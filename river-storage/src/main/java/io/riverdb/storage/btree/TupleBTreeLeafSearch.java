package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.btree.TupleKeyPrefix;
import java.nio.ByteBuffer;

/** Exact and leading-prefix searches over one validated tuple leaf. */
final class TupleBTreeLeafSearch {
  private TupleBTreeLeafSearch() { }

  static int initialIndex(TupleBTreeCursor cursor) {
    if (cursor.direction == TupleBTreeScanBounds.FORWARD) {
      if (cursor.lowerShape == null) return 0;
      int bound = prefixBoundValidated(
          cursor.page, cursor.pageStart, cursor.lowerPrefix,
          cursor.workspace, !cursor.lowerInclusive);
      return bound < 0 ? Integer.MIN_VALUE : bound;
    }
    if (cursor.upperShape == null) return cursor.limit - 1;
    int bound = prefixBoundValidated(
        cursor.page, cursor.pageStart,
        cursor.upperUsesLower ? cursor.lowerPrefix : cursor.upperPrefix,
        cursor.workspace, cursor.upperInclusive);
    return bound < 0 ? Integer.MIN_VALUE : bound - 1;
  }

  static StatusCode lookupExact(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace, TupleBTreeLookupResult result) {
    return lookupExact(
        page, start, schemaId, shape, key, keyOffset, keyLength,
        workspace, result, null, null);
  }

  static StatusCode lookupExact(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace, TupleBTreeLookupResult result,
      TupleBTreePageProvider provider, TupleBTreePageReference reference) {
    if (workspace == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    StatusCode status = TupleBTreePageAdmission.validate(
        page, start, schemaId, shape, TupleBTreePageCodec.TYPE_LEAF, workspace,
        provider, reference);
    if (!status.isOk()) return status;
    if (!TupleKeyCodec.matchesPhysicalIndexKey(key, keyOffset, keyLength, shape)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int index = TupleBTreePageSupport.lowerBoundLeaf(
        page, start, key, keyOffset, keyLength, workspace);
    if (index < 0) return StatusCode.INVARIANT_BROKEN;
    if (index >= workspace.header.entryCount()) return StatusCode.CONFLICT;
    if (!TupleBTreePageSupport.readLeaf(page, start, index, workspace)) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (TupleKeyCodec.compare(
        page, start + workspace.leaf.keyOffset(), workspace.leaf.keyLength(),
        key, keyOffset, keyLength) != 0) return StatusCode.CONFLICT;
    result.set(index, workspace.leaf.keyOffset(), workspace.leaf.keyLength(),
        workspace.leaf.logicalRowId());
    return StatusCode.OK;
  }

  static StatusCode prefixRange(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer prefix, int prefixOffset, int prefixLength, TupleShape prefixShape,
      TupleBTreeWorkspace workspace, TupleBTreeRange result) {
    if (workspace == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    StatusCode status = TupleBTreePageAdmission.validate(
        page, start, schemaId, shape, TupleBTreePageCodec.TYPE_LEAF, workspace);
    if (!status.isOk()) return status;
    if (!validPrefix(prefix, prefixOffset, prefixLength, prefixShape, shape)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    workspace.prefix.prepare(
        prefix, prefixOffset, prefixLength,
        prefixShape.partCount(), shape.partCount());
    int first = prefixBoundValidated(page, start, workspace.prefix, workspace, false);
    int limit = prefixBoundValidated(page, start, workspace.prefix, workspace, true);
    if (first < 0 || limit < 0) return StatusCode.INVARIANT_BROKEN;
    result.set(first, limit);
    return StatusCode.OK;
  }

  static int prefixBoundValidated(
      ByteBuffer page, int start, TupleKeyPrefix prefix,
      TupleBTreeWorkspace workspace, boolean upper) {
    int low = 0;
    int high = workspace.header.entryCount();
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (!TupleBTreePageSupport.readLeaf(page, start, middle, workspace)) return -1;
      int comparison = prefix.comparePhysical(
          page, start + workspace.leaf.keyOffset(), workspace.leaf.keyLength());
      if (comparison < 0 || upper && comparison == 0) low = middle + 1;
      else high = middle;
    }
    return low;
  }

  private static boolean validPrefix(
      ByteBuffer prefix, int offset, int length,
      TupleShape prefixShape, TupleShape fullShape) {
    if (prefixShape == null || fullShape == null
        || prefixShape.partCount() > fullShape.partCount()
        || !TupleKeyCodec.matchesShape(prefix, offset, length, prefixShape)) return false;
    for (int part = 0; part < prefixShape.partCount(); part++) {
      if (prefixShape.descriptorAt(part) != fullShape.descriptorAt(part)) return false;
    }
    return true;
  }
}
