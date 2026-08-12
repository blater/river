package io.riverdb.base.id;

/** Committed-visibility order, independent of a physical journal position. */
public record CommitSequence(long value) implements Comparable<CommitSequence> {
  public static final CommitSequence NONE = new CommitSequence(0);

  public CommitSequence {
    IdBounds.nonNegative("commit sequence", value);
  }

  public static CommitSequence of(long value) {
    return new CommitSequence(IdBounds.positive("commit sequence", value));
  }

  public boolean isValid() {
    return value != 0;
  }

  @Override
  public int compareTo(CommitSequence other) {
    return Long.compare(value, other.value);
  }
}
