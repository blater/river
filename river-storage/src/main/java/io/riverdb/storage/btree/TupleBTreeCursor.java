package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreeLeafEntry;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleBTreePageHeader;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Caller/session-owned reusable leaf cursor; returned entries borrow its current pinned page. */
public final class TupleBTreeCursor {
  static final int MODE_LOCAL = 1;
  static final int MODE_SCAN = 2;

  TupleBTreePageReference reference = new TupleBTreePageReference();
  TupleBTreePageReference transitionReference = new TupleBTreePageReference();
  final TupleBTreeWorkspace workspace = new TupleBTreeWorkspace();
  final TupleBTreePageHeader transitionHeader = new TupleBTreePageHeader();
  final TupleBTreeScanBounds convenienceBounds = new TupleBTreeScanBounds();
  final ByteBuffer lowerScratch = ByteBuffer.allocate(TupleKeyCodec.MAX_INDEX_USER_KEY_BYTES);
  final ByteBuffer upperScratch = ByteBuffer.allocate(TupleKeyCodec.MAX_INDEX_USER_KEY_BYTES);
  ByteBuffer page;
  int pageStart;
  TupleBTreePageHeader header;
  int index;
  int limit;
  int mode;
  TupleBTree tree;
  TupleShape lowerShape;
  int lowerLength;
  boolean lowerInclusive;
  TupleShape upperShape;
  int upperLength;
  boolean upperInclusive;
  boolean upperUsesLower;
  int direction;
  int rootPageId;
  long rootGeneration;

  public StatusCode open(
      ByteBuffer source,
      int start,
      TupleBTreePageHeader validatedHeader,
      int first,
      int exclusiveLimit) {
    StatusCode status = close();
    if (!status.isOk()) return status;
    if (source == null || validatedHeader == null
        || validatedHeader.type() != TupleBTreePageCodec.TYPE_LEAF
        || first < 0 || exclusiveLimit < first
        || exclusiveLimit > validatedHeader.entryCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    page = source;
    pageStart = start;
    header = validatedHeader;
    index = first;
    limit = exclusiveLimit;
    mode = MODE_LOCAL;
    return StatusCode.OK;
  }

  public StatusCode open(
      TupleBTree source, TupleBTreeScanBounds bounds,
      TupleBTreeTreeWorkspace treeWorkspace) {
    return TupleBTreeCursorOpen.open(this, source, bounds, treeWorkspace);
  }

  public StatusCode openPrefix(
      TupleBTree source, ByteBuffer key, int offset, int length, TupleShape prefixShape,
      TupleBTreeTreeWorkspace treeWorkspace) {
    StatusCode status = convenienceBounds.setPrefix(
        key, offset, length, prefixShape, TupleBTreeScanBounds.FORWARD);
    return status.isOk() ? open(source, convenienceBounds, treeWorkspace) : status;
  }

  public StatusCode openAll(TupleBTree source, TupleBTreeTreeWorkspace treeWorkspace) {
    StatusCode status = convenienceBounds.setAll(TupleBTreeScanBounds.FORWARD);
    return status.isOk() ? open(source, convenienceBounds, treeWorkspace) : status;
  }

  public StatusCode next(TupleBTreeLeafEntry result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (mode != MODE_LOCAL) return TupleBTreeCursorAdvance.next(this, result);
    if (page == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (index >= limit) {
      result.reset();
      return StatusCode.CONFLICT;
    }
    return TupleBTreePageCodec.readValidatedLeaf(
        page, pageStart, header, index++, result);
  }

  public StatusCode close() {
    StatusCode status = transitionReference.isAttached() && tree != null
        ? tree.provider().release(transitionReference) : StatusCode.OK;
    if (!status.isOk()) return status;
    transitionReference.reset();
    status = reference.isAttached() && tree != null
        ? tree.provider().release(reference) : StatusCode.OK;
    if (!status.isOk()) return status;
    reference.reset();
    page = null;
    pageStart = 0;
    header = null;
    transitionHeader.reset();
    index = 0;
    limit = 0;
    mode = 0;
    tree = null;
    TupleBTreeCursorOpen.clear(lowerScratch, lowerLength);
    TupleBTreeCursorOpen.clear(upperScratch, upperLength);
    lowerShape = null;
    lowerLength = 0;
    lowerInclusive = false;
    upperShape = null;
    upperLength = 0;
    upperInclusive = false;
    upperUsesLower = false;
    direction = 0;
    rootPageId = 0;
    rootGeneration = 0;
    return status;
  }

  public int pageId() { return reference.pageId(); }

  /** Borrowed read-only-by-contract view; invalid after cursor movement or close. */
  public ByteBuffer page() { return page; }
  public int pageStart() { return pageStart; }
}
