package io.riverdb.tx.api.version;

/**
 * Caller-owned prior-version append request. Input payload bytes are borrowed only for the append
 * call; the provider establishes its own stable lifetime before returning success.
 */
public final class VersionRecord {
  private long owningTransactionId;
  private long previousDatabaseIncarnationHigh;
  private long previousDatabaseIncarnationLow;
  private long previousStoreGeneration;
  private long previousAddress;
  private byte[] payload;
  private int payloadOffset;
  private int payloadLength;

  public VersionRecord set(
      long ownerTransactionId,
      long previousDatabaseHigh,
      long previousDatabaseLow,
      long previousGeneration,
      long previousOpaqueAddress,
      byte[] bytes,
      int offset,
      int length) {
    owningTransactionId = ownerTransactionId;
    previousDatabaseIncarnationHigh = previousDatabaseHigh;
    previousDatabaseIncarnationLow = previousDatabaseLow;
    previousStoreGeneration = previousGeneration;
    previousAddress = previousOpaqueAddress;
    payload = bytes;
    payloadOffset = offset;
    payloadLength = length;
    return this;
  }

  public long owningTransactionId() {
    return owningTransactionId;
  }

  public long previousDatabaseIncarnationHigh() {
    return previousDatabaseIncarnationHigh;
  }

  public long previousDatabaseIncarnationLow() {
    return previousDatabaseIncarnationLow;
  }

  public long previousStoreGeneration() {
    return previousStoreGeneration;
  }

  public long previousAddress() {
    return previousAddress;
  }

  public byte[] payloadArray() {
    return payload;
  }

  public int payloadOffset() {
    return payloadOffset;
  }

  public int payloadLength() {
    return payloadLength;
  }
}
