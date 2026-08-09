package io.riverdb.tx.api.version;

/**
 * Caller-owned opaque durable version address. Its two address words have provider-defined
 * meaning; this contract does not define a byte encoding or physical page layout.
 */
public final class VersionPointer {
  private long storeGeneration;
  private long address;

  public VersionPointer reset() {
    storeGeneration = 0;
    address = 0;
    return this;
  }

  /** Population hook for a provider or already-validated durable decoder. */
  public VersionPointer set(long generation, long opaqueAddress) {
    storeGeneration = generation;
    address = opaqueAddress;
    return this;
  }

  public long storeGeneration() {
    return storeGeneration;
  }

  public long address() {
    return address;
  }

  public boolean isValid() {
    return storeGeneration != 0 && address != 0;
  }
}
