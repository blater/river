package io.riverdb.engine.runtime;

/** Caller-owned physical inputs for checked database resource-plan compilation. */
public final class DatabaseResourcePlanRequest {
  long maximumAccountedBytes;
  long fixedRuntimeBytes;
  long progressReserveBytes;
  long minimumOwnerBytes;
  long maximumDeliveryAccountedBytes;
  long maximumDeliveryWriteEntries;
  long maximumDeliveryStagedPages;
  long maximumDeliveryWalBytes;
  int maximumOwners;
  long writeEntryCapacity;
  long lockProviderBytes;
  long stagedPageCapacity;
  long walByteCapacity;

  public DatabaseResourcePlanRequest memory(
      long maximum, long fixed, long progress, long minimumOwner, long maximumDelivery) {
    maximumAccountedBytes = maximum;
    fixedRuntimeBytes = fixed;
    progressReserveBytes = progress;
    minimumOwnerBytes = minimumOwner;
    maximumDeliveryAccountedBytes = maximumDelivery;
    return this;
  }

  public DatabaseResourcePlanRequest maximumDelivery(
      long writeEntries, long stagedPages, long walBytes) {
    maximumDeliveryWriteEntries = writeEntries;
    maximumDeliveryStagedPages = stagedPages;
    maximumDeliveryWalBytes = walBytes;
    return this;
  }

  public DatabaseResourcePlanRequest capacity(
      int owners, long writeEntries, long stagedPages, long walBytes) {
    maximumOwners = owners;
    writeEntryCapacity = writeEntries;
    stagedPageCapacity = stagedPages;
    walByteCapacity = walBytes;
    return this;
  }

  public DatabaseResourcePlanRequest lockProviderBytes(long bytes) {
    lockProviderBytes = bytes;
    return this;
  }

  public void reset() {
    maximumAccountedBytes = fixedRuntimeBytes = progressReserveBytes = 0;
    minimumOwnerBytes = maximumDeliveryAccountedBytes = 0;
    maximumDeliveryWriteEntries = 0;
    maximumDeliveryStagedPages = maximumDeliveryWalBytes = 0;
    maximumOwners = 0;
    writeEntryCapacity = stagedPageCapacity = walByteCapacity = lockProviderBytes = 0;
  }
}
