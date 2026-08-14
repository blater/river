package io.riverdb.engine.table;

import io.riverdb.storage.heap.HeapInsertResult;
import java.nio.ByteBuffer;

/** Validates the external shape of compact logical commit requests before admission. */
final class IndexedLogicalRequestValidator {
  private IndexedLogicalRequestValidator() {}

  static boolean validInsert(
      long transactionId,
      long commitSequence,
      long publishedCommitSequence,
      long key,
      ByteBuffer row,
      HeapInsertResult result) {
    return transactionId > 0
        && commitSequence > publishedCommitSequence
        && key != Long.MAX_VALUE
        && row != null
        && row.hasRemaining()
        && result != null;
  }

  static boolean validPending(
      long transactionId,
      long commitSequence,
      long publishedCommitSequence,
      PendingMutationBuffer mutations,
      HeapInsertResult result) {
    return transactionId > 0
        && commitSequence > publishedCommitSequence
        && mutations != null
        && mutations.count() > 0
        && result != null;
  }

  static boolean validRawInsert(
      long transactionId,
      long commitSequence,
      long publishedCommitSequence,
      long[] keys,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int insertCount,
      HeapInsertResult result) {
    return transactionId > 0
        && commitSequence > publishedCommitSequence
        && keys != null
        && rows != null
        && rowStride > 0
        && rowLengths != null
        && insertCount > 1
        && insertCount <= keys.length
        && insertCount <= rowLengths.length
        && result != null;
  }

  static boolean validRawMutation(
      long transactionId,
      long commitSequence,
      long publishedCommitSequence,
      int[] operations,
      long[] keys,
      int[] previousRowIds,
      ByteBuffer rows,
      int rowStride,
      int[] rowLengths,
      int mutationCount,
      HeapInsertResult result) {
    return transactionId > 0
        && commitSequence > publishedCommitSequence
        && operations != null
        && keys != null
        && previousRowIds != null
        && rows != null
        && rowStride > 0
        && rowLengths != null
        && mutationCount > 0
        && mutationCount <= operations.length
        && mutationCount <= keys.length
        && mutationCount <= previousRowIds.length
        && mutationCount <= rowLengths.length
        && result != null;
  }
}
