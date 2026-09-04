package io.riverdb.storage.btree;

import io.riverdb.format.btree.TupleBTreePageCodec;

/** Exact encoded occupancy of one already validated tuple page. */
final class TupleBTreePageOccupancy {
  private TupleBTreePageOccupancy() { }

  static boolean accepts(int keyLength, TupleBTreeWorkspace workspace) {
    long resultingFreeStart = (long) TupleBTreePageCodec.HEADER_BYTES
        + (long) (workspace.header.entryCount() + 1) * TupleBTreePageCodec.SLOT_BYTES;
    long resultingFreeEnd = (long) workspace.header.freeEnd() - keyLength;
    return resultingFreeStart <= resultingFreeEnd;
  }
}
