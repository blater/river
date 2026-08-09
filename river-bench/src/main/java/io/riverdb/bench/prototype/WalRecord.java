package io.riverdb.bench.prototype;

/** Caller-owned decoded record carrier. */
public final class WalRecord {
  long sequence;
  long transactionId;
  long value;

  public long sequence() {
    return sequence;
  }

  public long transactionId() {
    return transactionId;
  }

  public long value() {
    return value;
  }
}
