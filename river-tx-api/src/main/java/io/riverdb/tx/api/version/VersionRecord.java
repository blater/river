package io.riverdb.tx.api.version;

/**
 * Caller-owned prior-version carrier. Input payload bytes are borrowed for the call; a provider
 * may return a borrowed stable-lifetime array view from {@code readVersion}. Callers must not
 * mutate or retain a returned array beyond the provider's documented reclamation boundary.
 */
public final class VersionRecord {
  private long owningTransactionId;
  private long cachedCommitSequence;
  private long previousStoreGeneration;
  private long previousAddress;
  private byte[] payload;
  private int payloadOffset;
  private int payloadLength;

  public VersionRecord set(
      long ownerTransactionId,
      long commitSequence,
      long previousGeneration,
      long previousOpaqueAddress,
      byte[] bytes,
      int offset,
      int length) {
    owningTransactionId = ownerTransactionId;
    cachedCommitSequence = commitSequence;
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

  public long cachedCommitSequence() {
    return cachedCommitSequence;
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
