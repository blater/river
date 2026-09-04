package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Exact and leading-prefix searches over one validated tuple leaf. */
final class TupleBTreeLeafSearch {
  private TupleBTreeLeafSearch() { }

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
    int first = prefixBound(
        page, start, prefix, prefixOffset, prefixLength,
        prefixShape.partCount(), workspace, false);
    int limit = prefixBound(
        page, start, prefix, prefixOffset, prefixLength,
        prefixShape.partCount(), workspace, true);
    if (first < 0 || limit < 0) return StatusCode.INVARIANT_BROKEN;
    result.set(first, limit);
    return StatusCode.OK;
  }

  private static int prefixBound(
      ByteBuffer page, int start, ByteBuffer prefix, int prefixOffset, int prefixLength,
      int prefixParts, TupleBTreeWorkspace workspace, boolean upper) {
    int low = 0;
    int high = workspace.header.entryCount();
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (!TupleBTreePageSupport.readLeaf(page, start, middle, workspace)) return -1;
      int comparison = TupleKeyCodec.comparePrefix(
          page, start + workspace.leaf.keyOffset(), workspace.leaf.keyLength(),
          prefix, prefixOffset, prefixLength, prefixParts);
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
