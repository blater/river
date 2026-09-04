package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Caller-owned structurally bounded path and scratch storage reused by whole-tree operations. */
public final class TupleBTreeTreeWorkspace {
  final TupleBTreeWorkspace page = new TupleBTreeWorkspace();
  final TupleBTreeWorkspace otherPage = new TupleBTreeWorkspace();
  final TupleBTreeLookupResult pageLookup = new TupleBTreeLookupResult();
  final TupleBTreeSplitResult split = new TupleBTreeSplitResult();
  final TupleBTreePageReference current = new TupleBTreePageReference();
  final TupleBTreePageReference other = new TupleBTreePageReference();
  final TupleBTreeGraphState graph = new TupleBTreeGraphState();
  final ByteBuffer pageScratch;
  final ByteBuffer keyScratch;
  final int[] pathPageIds;
  final int[] pathChildOrdinals;
  final int[] pathNextChildOrdinals;
  int pathDepth;
  int leafPageId;
  int propagatedRightPageId;

  public TupleBTreeTreeWorkspace(
      ByteBuffer reusablePage,
      ByteBuffer reusableKey,
      int[] reusablePathPageIds,
      int[] reusablePathChildOrdinals,
      int[] reusablePathNextChildOrdinals) {
    pageScratch = reusablePage;
    keyScratch = reusableKey;
    pathPageIds = reusablePathPageIds;
    pathChildOrdinals = reusablePathChildOrdinals;
    pathNextChildOrdinals = reusablePathNextChildOrdinals;
  }

  boolean isValid() {
    return pageScratch != null && !pageScratch.isReadOnly()
        && pageScratch.limit() >= PageCodec.MAX_PAYLOAD_BYTES
        && keyScratch != null && !keyScratch.isReadOnly()
        && keyScratch.limit() >= TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES
        && pageScratch != keyScratch
        && pathPageIds != null && pathPageIds.length >= TupleBTreeStructure.MAXIMUM_LEVELS
        && pathChildOrdinals != null
        && pathChildOrdinals.length >= TupleBTreeStructure.MAXIMUM_LEVELS
        && pathNextChildOrdinals != null
        && pathNextChildOrdinals.length >= TupleBTreeStructure.MAXIMUM_LEVELS
        && !current.isAttached() && !other.isAttached();
  }

  /** Retries release of provider borrows retained after an earlier release failure. */
  public StatusCode releaseRetained(TupleBTreePageProvider provider) {
    if (provider == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = TupleBTreeProviderAccess.release(provider, current, StatusCode.OK);
    return TupleBTreeProviderAccess.release(provider, other, status);
  }

  void resetPath() {
    pathDepth = 0;
    leafPageId = 0;
    propagatedRightPageId = 0;
    split.reset();
    graph.reset();
  }
}
