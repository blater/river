package io.riverdb.journal.api.mapping;

import io.riverdb.base.id.DatabaseIncarnation;

/** Exclusive replica-local WAL byte boundary known stable in one exact lineage. */
public record DurableWalEnd(
    DatabaseIncarnation databaseIncarnation,
    long walGeneration,
    long durableEndLsnExclusive) {
  public static final DurableWalEnd NONE = new DurableWalEnd(
      DatabaseIncarnation.NONE, 0, 0);

  public DurableWalEnd {
    boolean none = databaseIncarnation.equals(DatabaseIncarnation.NONE)
        && walGeneration == 0
        && durableEndLsnExclusive == 0;
    if (!none && (!databaseIncarnation.isValid()
        || walGeneration <= 0
        || durableEndLsnExclusive < 0)) {
      throw new IllegalArgumentException("invalid lineage-qualified durable WAL end");
    }
  }

  public boolean isValid() {
    return walGeneration != 0;
  }

  public boolean covers(WalRecordRange range) {
    return isValid()
        && databaseIncarnation.equals(range.databaseIncarnation())
        && walGeneration == range.walGeneration()
        && range.recordEndLsnExclusive() <= durableEndLsnExclusive;
  }
}
