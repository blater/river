package io.riverdb.base.id;

/** Logical journal identity independent of any node's file or byte layout. */
public record JournalPosition(
    DatabaseIncarnation incarnation,
    long generation,
    long sequence) {
  public static final JournalPosition NONE =
      new JournalPosition(DatabaseIncarnation.NONE, 0, 0);

  public JournalPosition {
    boolean none = incarnation.equals(DatabaseIncarnation.NONE) && generation == 0 && sequence == 0;
    if (!none && (!incarnation.isValid() || generation <= 0 || sequence < 0)) {
      throw new IllegalArgumentException(
          "journal position requires incarnation, positive generation, and non-negative sequence");
    }
  }

  public static JournalPosition of(
      DatabaseIncarnation incarnation,
      long generation,
      long sequence) {
    return new JournalPosition(incarnation, generation, sequence);
  }

  public boolean isValid() {
    return generation != 0;
  }

  public boolean isComparableTo(JournalPosition other) {
    return incarnation.equals(other.incarnation) && generation == other.generation;
  }

  public int compareSequence(JournalPosition other) {
    if (!isComparableTo(other)) {
      throw new IllegalArgumentException("journal positions belong to different histories");
    }
    return Long.compare(sequence, other.sequence);
  }
}
