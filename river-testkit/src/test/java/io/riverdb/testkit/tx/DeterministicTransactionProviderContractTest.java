package io.riverdb.testkit.tx;

import io.riverdb.tx.api.Visibility;
import io.riverdb.tx.api.lock.LockService;
import io.riverdb.tx.spi.RecoveryTransactionStorage;
import io.riverdb.tx.spi.TransactionStorage;

final class DeterministicTransactionProviderContractTest
    extends TransactionProviderContractTest {
  private static final long DATABASE_HIGH = 11;
  private static final long DATABASE_LOW = 12;
  private static final long STORE_GENERATION = 3;

  @Override
  protected TransactionProviderHarness openHarness(
      int transactionCapacity,
      int versionCapacity,
      int maxVersionBytes,
      int lockCapacity) {
    DeterministicTransactionProvider provider = new DeterministicTransactionProvider(
        DATABASE_HIGH,
        DATABASE_LOW,
        STORE_GENERATION,
        transactionCapacity,
        versionCapacity,
        maxVersionBytes,
        lockCapacity);
    return new Harness(provider);
  }

  private record Harness(DeterministicTransactionProvider provider)
      implements TransactionProviderHarness {
    @Override
    public TransactionStorage storage() {
      return provider;
    }

    @Override
    public RecoveryTransactionStorage recoveryStorage() {
      return provider;
    }

    @Override
    public Visibility visibility() {
      return provider;
    }

    @Override
    public LockService locks() {
      return provider;
    }

    @Override
    public long databaseIncarnationHigh() {
      return DATABASE_HIGH;
    }

    @Override
    public long databaseIncarnationLow() {
      return DATABASE_LOW;
    }

    @Override
    public long versionStoreGeneration() {
      return STORE_GENERATION;
    }
  }
}
