package io.riverdb.journal.api.frontier;

/** Atomic caller-owned snapshot containing only frontiers owned by a journal provider. */
public final class JournalFrontierSnapshot {
  private long databaseIncarnationHigh;
  private long databaseIncarnationLow;
  private long journalGeneration;
  private long preparedSequence;
  private long memoryReplicatedSequence;
  private long journalCommittedSequence;
  private long localWalDurableSequence;
  private long quorumWalDurableSequence;

  public JournalFrontierSnapshot set(
      long databaseHigh,
      long databaseLow,
      long generation,
      long prepared,
      long memoryReplicated,
      long committed,
      long localDurable,
      long quorumDurable) {
    databaseIncarnationHigh = databaseHigh;
    databaseIncarnationLow = databaseLow;
    journalGeneration = generation;
    preparedSequence = prepared;
    memoryReplicatedSequence = memoryReplicated;
    journalCommittedSequence = committed;
    localWalDurableSequence = localDurable;
    quorumWalDurableSequence = quorumDurable;
    return this;
  }

  public long databaseIncarnationHigh() {
    return databaseIncarnationHigh;
  }

  public long databaseIncarnationLow() {
    return databaseIncarnationLow;
  }

  public long journalGeneration() {
    return journalGeneration;
  }

  public long preparedSequence() {
    return preparedSequence;
  }

  public long memoryReplicatedSequence() {
    return memoryReplicatedSequence;
  }

  public long journalCommittedSequence() {
    return journalCommittedSequence;
  }

  public long localWalDurableSequence() {
    return localWalDurableSequence;
  }

  public long quorumWalDurableSequence() {
    return quorumWalDurableSequence;
  }
}
