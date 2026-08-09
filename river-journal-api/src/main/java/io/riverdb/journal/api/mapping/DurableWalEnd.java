package io.riverdb.journal.api.mapping;

import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;

/** Exclusive replica-local WAL byte boundary known stable in one exact lineage. */
public record DurableWalEnd(
    DatabaseIncarnation databaseIncarnation,
    WalGeneration walGeneration,
    long durableEndLsnExclusive) {
  public static final DurableWalEnd NONE = new DurableWalEnd(
      DatabaseIncarnation.NONE, WalGeneration.NONE, 0);

  public DurableWalEnd {
    boolean none = databaseIncarnation.equals(DatabaseIncarnation.NONE)
        && walGeneration.equals(WalGeneration.NONE)
        && durableEndLsnExclusive == 0;
    if (!none && (!databaseIncarnation.isValid()
        || !walGeneration.isValid()
        || durableEndLsnExclusive < 0)) {
      throw new IllegalArgumentException("invalid lineage-qualified durable WAL end");
    }
  }

  public boolean isValid() {
    return walGeneration.isValid();
  }

  public boolean covers(WalRecordRange range) {
    return isValid()
        && databaseIncarnation.equals(range.databaseIncarnation())
        && walGeneration.equals(range.walGeneration())
        && range.recordEndLsnExclusive() <= durableEndLsnExclusive;
  }
}
