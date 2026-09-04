package io.riverdb.wal.local;

/** Logical WAL records partitioned into independently decided transactions. */
public interface LocalWalDecisionBatch extends LocalWalRecordBatch {
  int transactionCount();

  int transactionEndRecord(int transaction);

  long transactionId(int transaction);

  long commitSequence(int transaction);
}
