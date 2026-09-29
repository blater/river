package io.riverdb.engine.table;

import io.riverdb.format.page.PageCodec;

/** Tests retained frame borrows before an overflow reference is recycled. */
final class IndexedPageFramePinVisibility {
  private IndexedPageFramePinVisibility() { }

  static boolean hasPreRetirementTupleReference(
      IndexedPageFrame[] frames, long ownerKeyId,
      int overflowPageId, long retirementSequence) {
    for (IndexedPageFrame frame : frames) {
      if (frame == null || frame.pageId == 0 || frame.pinCount == 0) continue;
      if (frame.pageId == overflowPageId) return true;
      if (frame.payloadKind == PageCodec.PAYLOAD_KIND_TUPLE_BTREE
          && frame.ownerKeyId == ownerKeyId
          && frame.validFromCommitSequence < retirementSequence) return true;
    }
    return false;
  }
}
