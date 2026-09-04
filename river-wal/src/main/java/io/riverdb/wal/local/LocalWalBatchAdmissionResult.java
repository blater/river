package io.riverdb.wal.local;

/** Caller-owned exact range admitted for one streamed WAL batch. */
final class LocalWalBatchAdmissionResult {
  private long endOffset;
  private int recordCount;

  long endOffset() { return endOffset; }
  int recordCount() { return recordCount; }

  void reset() {
    endOffset = 0;
    recordCount = 0;
  }

  void set(long admittedEndOffset, int admittedRecordCount) {
    endOffset = admittedEndOffset;
    recordCount = admittedRecordCount;
  }
}
