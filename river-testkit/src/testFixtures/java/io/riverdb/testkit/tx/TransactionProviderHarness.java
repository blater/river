package io.riverdb.testkit.tx;

import io.riverdb.tx.api.Visibility;
import io.riverdb.tx.api.lock.LockService;
import io.riverdb.tx.spi.RecoveryTransactionStorage;
import io.riverdb.tx.spi.TransactionStorage;

/** Provider roles needed by the implementation-neutral transaction semantic suite. */
public interface TransactionProviderHarness {
  TransactionStorage storage();

  RecoveryTransactionStorage recoveryStorage();

  Visibility visibility();

  LockService locks();

  long databaseIncarnationHigh();

  long databaseIncarnationLow();

  long versionStoreGeneration();
}
