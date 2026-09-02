package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import java.nio.ByteBuffer;

/** Allocation-free page-local routing and mutation over tuple internal pages. */
public final class TupleBTreeInternalPage {
  private TupleBTreeInternalPage() { }

  public static int childForKey(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength, TupleBTreeWorkspace workspace) {
    return TupleBTreeInternalSearch.childForKey(
        page, start, schemaId, shape, key, keyOffset, keyLength, workspace);
  }

  public static StatusCode insert(
      ByteBuffer page, int start, ByteBuffer scratch, int scratchStart,
      long schemaId, TupleShape shape,
      ByteBuffer separator, int separatorOffset, int separatorLength,
      int rightChildPageId, TupleBTreeWorkspace workspace) {
    return TupleBTreeInternalMutation.insert(
        page, start, scratch, scratchStart, schemaId, shape,
        separator, separatorOffset, separatorLength, rightChildPageId, workspace);
  }

  public static StatusCode splitInsert(
      ByteBuffer source, int sourceStart,
      ByteBuffer left, int leftStart,
      ByteBuffer right, int rightStart,
      long schemaId, TupleShape shape,
      ByteBuffer separator, int separatorOffset, int separatorLength,
      int rightChildPageId, TupleBTreeWorkspace workspace,
      TupleBTreeSplitResult result) {
    return TupleBTreeInternalSplit.splitInsert(
        source, sourceStart, left, leftStart, right, rightStart,
        schemaId, shape, separator, separatorOffset, separatorLength,
        rightChildPageId, workspace, result);
  }
}
