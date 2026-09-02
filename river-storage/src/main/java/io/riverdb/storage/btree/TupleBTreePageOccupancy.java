package io.riverdb.storage.btree;

import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Exact encoded occupancy of one already validated tuple page. */
final class TupleBTreePageOccupancy {
  private TupleBTreePageOccupancy() { }

  static boolean acceptsLeaf(
      ByteBuffer page, int start, int keyLength, TupleBTreeWorkspace workspace) {
    int keyBytes = 0;
    for (int index = 0; index < workspace.header.entryCount(); index++) {
      TupleBTreePageSupport.readLeaf(page, start, index, workspace);
      keyBytes += workspace.leaf.keyLength();
    }
    return accepts(keyBytes, keyLength, workspace);
  }

  static boolean acceptsInternal(
      ByteBuffer page, int start, int keyLength, TupleBTreeWorkspace workspace) {
    int keyBytes = 0;
    for (int index = 0; index < workspace.header.entryCount(); index++) {
      TupleBTreePageSupport.readInternal(page, start, index, workspace);
      keyBytes += workspace.internal.keyLength();
    }
    return accepts(keyBytes, keyLength, workspace);
  }

  private static boolean accepts(
      int keyBytes, int insertedKeyBytes, TupleBTreeWorkspace workspace) {
    long bytes = (long) TupleBTreePageCodec.HEADER_BYTES
        + (long) (workspace.header.entryCount() + 1) * TupleBTreePageCodec.SLOT_BYTES
        + keyBytes + insertedKeyBytes + workspace.header.highKeyLength();
    return bytes <= PageCodec.MAX_PAYLOAD_BYTES;
  }
}
