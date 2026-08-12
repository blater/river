package io.riverdb.journal.api.mapping;

import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;

/** Replica-local WAL bytes occupied by one complete record: {@code [start, end)}. */
public record WalRecordRange(
    DatabaseIncarnation databaseIncarnation,
    WalGeneration walGeneration,
    long recordStartLsn,
    long recordEndLsnExclusive) {
  public static final WalRecordRange NONE = new WalRecordRange(
      DatabaseIncarnation.NONE, WalGeneration.NONE, 0, 0);

  public WalRecordRange {
    boolean none = databaseIncarnation.equals(DatabaseIncarnation.NONE)
        && walGeneration.equals(WalGeneration.NONE)
        && recordStartLsn == 0
        && recordEndLsnExclusive == 0;
    if (!none && (!databaseIncarnation.isValid()
        || !walGeneration.isValid()
        || recordStartLsn < 0
        || recordEndLsnExclusive <= recordStartLsn)) {
      throw new IllegalArgumentException("invalid lineage-qualified WAL record range");
    }
  }

  public boolean isValid() {
    return walGeneration.isValid();
  }

  public long length() {
    return recordEndLsnExclusive - recordStartLsn;
  }
}
