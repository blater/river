package io.riverdb.base.id;

/** A monotonic transaction identifier. Zero is reserved for {@link #NONE}. */
public record TransactionId(long value) implements Comparable<TransactionId> {
  public static final TransactionId NONE = new TransactionId(0);

  public TransactionId {
    IdBounds.nonNegative("transaction id", value);
  }

  public static TransactionId of(long value) {
    return new TransactionId(IdBounds.positive("transaction id", value));
  }

  public boolean isValid() {
    return value != 0;
  }

  @Override
  public int compareTo(TransactionId other) {
    return Long.compare(value, other.value);
  }
}
