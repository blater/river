package io.riverdb.tx.api.version;

/**
 * Caller-owned prior-version read destination and metadata output. The provider copies into the
 * configured destination, so reclamation can never mutate bytes retained by the caller.
 */
public final class VersionReadResult {
  private byte[] destination;
  private int destinationOffset;
  private int destinationCapacity;
  private long owningTransactionId;
  private long previousDatabaseIncarnationHigh;
  private long previousDatabaseIncarnationLow;
  private long previousStoreGeneration;
  private long previousAddress;
  private int payloadLength;
  private int requiredPayloadBytes;

  public VersionReadResult useDestination(byte[] bytes, int offset, int capacity) {
    destination = bytes;
    destinationOffset = offset;
    destinationCapacity = capacity;
    return resetMetadata();
  }

  public VersionReadResult resetMetadata() {
    owningTransactionId = 0;
    previousDatabaseIncarnationHigh = 0;
    previousDatabaseIncarnationLow = 0;
    previousStoreGeneration = 0;
    previousAddress = 0;
    payloadLength = 0;
    requiredPayloadBytes = 0;
    return this;
  }

  /** Provider population hook after copying into the configured caller-owned destination. */
  public VersionReadResult setMetadata(
      long ownerTransactionId,
      long previousDatabaseHigh,
      long previousDatabaseLow,
      long previousGeneration,
      long previousOpaqueAddress,
      int bytes) {
    owningTransactionId = ownerTransactionId;
    previousDatabaseIncarnationHigh = previousDatabaseHigh;
    previousDatabaseIncarnationLow = previousDatabaseLow;
    previousStoreGeneration = previousGeneration;
    previousAddress = previousOpaqueAddress;
    payloadLength = bytes;
    requiredPayloadBytes = bytes;
    return this;
  }

  /** Provider population hook for a destination-too-small result. */
  public VersionReadResult requirePayloadBytes(int bytes) {
    requiredPayloadBytes = bytes;
    return this;
  }

  public byte[] destinationArray() {
    return destination;
  }

  public int destinationOffset() {
    return destinationOffset;
  }

  public int destinationCapacity() {
    return destinationCapacity;
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

  public int payloadLength() {
    return payloadLength;
  }

  public int requiredPayloadBytes() {
    return requiredPayloadBytes;
  }
}
