package io.riverdb.storage.btree;

import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.page.PageCodec;

/** Encoded-byte occupancy and balance selection shared by tuple split policies. */
final class TupleBTreeSplitOccupancy {
  private TupleBTreeSplitOccupancy() { }

  static int bytes(int entries, int keyBytes, int fenceBytes) {
    long used = (long) TupleBTreePageCodec.HEADER_BYTES
        + (long) entries * TupleBTreePageCodec.SLOT_BYTES + keyBytes + fenceBytes;
    return used <= Integer.MAX_VALUE ? (int) used : Integer.MAX_VALUE;
  }

  static boolean fits(int bytes) {
    return bytes <= PageCodec.MAX_PAYLOAD_BYTES;
  }

  static int imbalance(int leftBytes, int rightBytes) {
    return Math.abs(leftBytes - rightBytes);
  }
}
