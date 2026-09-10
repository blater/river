package io.riverdb.storage.btree;

import io.riverdb.format.btree.TupleKeyPrefix;
import java.nio.ByteBuffer;

/** Routes to the leftmost child that may contain a leading tuple prefix. */
final class TupleBTreeInternalPrefixSearch {
  private TupleBTreeInternalPrefixSearch() { }

  static int childValidated(
      ByteBuffer page, int start, TupleKeyPrefix prefixState, TupleBTreeWorkspace workspace) {
    int low = 0;
    int high = workspace.header.entryCount();
    while (low < high) {
      int middle = (low + high) >>> 1;
      TupleBTreePageSupport.readInternal(page, start, middle, workspace);
      int comparison = prefixState.comparePhysical(
          page, start + workspace.internal.keyOffset(), workspace.internal.keyLength());
      if (comparison < 0) low = middle + 1;
      else high = middle;
    }
    if (low == 0) return workspace.header.firstChildPageId();
    TupleBTreePageSupport.readInternal(page, start, low - 1, workspace);
    return workspace.internal.rightChildPageId();
  }

  static int upperChildValidated(
      ByteBuffer page, int start, TupleKeyPrefix prefixState, TupleBTreeWorkspace workspace) {
    int low = 0;
    int high = workspace.header.entryCount();
    while (low < high) {
      int middle = (low + high) >>> 1;
      TupleBTreePageSupport.readInternal(page, start, middle, workspace);
      int comparison = prefixState.comparePhysical(
          page, start + workspace.internal.keyOffset(), workspace.internal.keyLength());
      if (comparison <= 0) low = middle + 1;
      else high = middle;
    }
    if (low == 0) return workspace.header.firstChildPageId();
    TupleBTreePageSupport.readInternal(page, start, low - 1, workspace);
    return workspace.internal.rightChildPageId();
  }
}
