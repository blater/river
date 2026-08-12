package io.riverdb.tx.api.version;

/**
 * Caller-owned lineage-qualified opaque durable version address. The database incarnation
 * prevents cross-history aliasing; store generation and address remain provider-defined. This
 * contract does not define a byte encoding or physical page layout.
 */
public final class VersionPointer {
  private long databaseIncarnationHigh;
  private long databaseIncarnationLow;
  private long storeGeneration;
  private long address;

  public VersionPointer reset() {
    databaseIncarnationHigh = 0;
    databaseIncarnationLow = 0;
    storeGeneration = 0;
    address = 0;
    return this;
  }

  /** Population hook for a provider or already-validated durable decoder. */
  public VersionPointer set(
      long databaseHigh,
      long databaseLow,
      long generation,
      long opaqueAddress) {
    databaseIncarnationHigh = databaseHigh;
    databaseIncarnationLow = databaseLow;
    storeGeneration = generation;
    address = opaqueAddress;
    return this;
  }

  public long databaseIncarnationHigh() {
    return databaseIncarnationHigh;
  }

  public long databaseIncarnationLow() {
    return databaseIncarnationLow;
  }

  public long storeGeneration() {
    return storeGeneration;
  }

  public long address() {
    return address;
  }

  public boolean isValid() {
    return (databaseIncarnationHigh != 0 || databaseIncarnationLow != 0)
        && storeGeneration != 0
        && address != 0;
  }
}
