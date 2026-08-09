package io.riverdb.journal.api.mapping;

import io.riverdb.base.id.DatabaseIncarnation;

/** Replica-local WAL bytes occupied by one complete record: {@code [start, end)}. */
public record WalRecordRange(
    DatabaseIncarnation databaseIncarnation,
    long walGeneration,
    long recordStartLsn,
    long recordEndLsnExclusive) {
  public static final WalRecordRange NONE = new WalRecordRange(
      DatabaseIncarnation.NONE, 0, 0, 0);

  public WalRecordRange {
    boolean none = databaseIncarnation.equals(DatabaseIncarnation.NONE)
        && walGeneration == 0
        && recordStartLsn == 0
        && recordEndLsnExclusive == 0;
    if (!none && (!databaseIncarnation.isValid()
        || walGeneration <= 0
        || recordStartLsn < 0
        || recordEndLsnExclusive <= recordStartLsn)) {
      throw new IllegalArgumentException("invalid lineage-qualified WAL record range");
    }
  }

  public boolean isValid() {
    return walGeneration != 0;
  }

  public long length() {
    return recordEndLsnExclusive - recordStartLsn;
  }
}
