package io.riverdb.storage.btree;

import java.nio.ByteBuffer;

/** Copies a simulated split separator across the provider-borrow lifetime boundary. */
final class TupleBTreePreflightSeparator {
  private TupleBTreePreflightSeparator() { }

  static int leaf(
      ByteBuffer source, int start, int insertion, int splitAt,
      ByteBuffer inserted, int insertedLength, TupleBTreeWorkspace workspace) {
    if (splitAt == insertion) return insertedLength;
    int sourceIndex = splitAt < insertion ? splitAt : splitAt - 1;
    TupleBTreePageSupport.readLeaf(source, start, sourceIndex, workspace);
    int length = workspace.leaf.keyLength();
    return copy(source, start + workspace.leaf.keyOffset(), length, inserted) ? length : 0;
  }

  static int internal(
      ByteBuffer source, int start, int insertion, int promoted,
      ByteBuffer inserted, int insertedLength, TupleBTreeWorkspace workspace) {
    if (promoted == insertion) return insertedLength;
    int sourceIndex = promoted < insertion ? promoted : promoted - 1;
    TupleBTreePageSupport.readInternal(source, start, sourceIndex, workspace);
    int length = workspace.internal.keyLength();
    return copy(source, start + workspace.internal.keyOffset(), length, inserted) ? length : 0;
  }

  private static boolean copy(
      ByteBuffer source, int offset, int length, ByteBuffer target) {
    if (length <= 0 || length > target.limit()) return false;
    for (int index = 0; index < length; index++) target.put(index, source.get(offset + index));
    return true;
  }
}
