package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import java.nio.ByteBuffer;

/** Allocation-free page-local operations over physical tuple-key leaves. */
public final class TupleBTreeLeafPage {
  private TupleBTreeLeafPage() { }

  public static StatusCode lookupExact(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace, TupleBTreeLookupResult result) {
    return TupleBTreeLeafSearch.lookupExact(
        page, start, schemaId, shape, key, keyOffset, keyLength, workspace, result);
  }

  public static StatusCode prefixRange(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer prefix, int prefixOffset, int prefixLength, TupleShape prefixShape,
      TupleBTreeWorkspace workspace, TupleBTreeRange result) {
    return TupleBTreeLeafSearch.prefixRange(
        page, start, schemaId, shape, prefix, prefixOffset, prefixLength,
        prefixShape, workspace, result);
  }

  public static StatusCode insert(
      ByteBuffer page, int start, ByteBuffer scratch, int scratchStart,
      long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace) {
    return TupleBTreeLeafMutation.insert(
        page, start, scratch, scratchStart, schemaId, shape,
        key, keyOffset, keyLength, workspace);
  }

  public static StatusCode delete(
      ByteBuffer page, int start, ByteBuffer scratch, int scratchStart,
      long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace) {
    return TupleBTreeLeafMutation.delete(
        page, start, scratch, scratchStart, schemaId, shape,
        key, keyOffset, keyLength, workspace);
  }

  public static StatusCode splitInsert(
      ByteBuffer source, int sourceStart,
      ByteBuffer left, int leftStart,
      ByteBuffer right, int rightStart,
      int leftPageId, int rightPageId, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace, TupleBTreeSplitResult result) {
    return TupleBTreeLeafSplit.splitInsert(
        source, sourceStart, left, leftStart, right, rightStart,
        leftPageId, rightPageId, schemaId, shape,
        key, keyOffset, keyLength, workspace, result);
  }
}
