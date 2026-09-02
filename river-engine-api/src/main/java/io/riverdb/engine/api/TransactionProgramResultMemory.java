package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Coordinates one exact lease shared by result metadata and value storage. */
final class TransactionProgramResultMemory implements RetainedMemoryLease {
  private final RetainedMemoryLease memory;
  private long metadataBytes;
  private long arenaBytes;

  TransactionProgramResultMemory(RetainedMemoryLease retainedMemory) {
    memory = retainedMemory;
  }

  StatusCode resizeMetadata(long bytes) {
    StatusCode status = resize(bytes, arenaBytes);
    if (status.isOk()) metadataBytes = bytes;
    return status;
  }

  long metadataBytes() { return metadataBytes; }
  long arenaBytes() { return arenaBytes; }

  @Override
  public StatusCode resize(long bytes) {
    StatusCode status = resize(metadataBytes, bytes);
    if (status.isOk()) arenaBytes = bytes;
    return status;
  }

  @Override
  public StatusCode awaitResize(long bytes) { return resize(bytes); }

  @Override
  public long retainedBytes() { return metadataBytes + arenaBytes; }

  private StatusCode resize(long metadata, long arena) {
    if (metadata < 0 || arena < 0 || metadata > Long.MAX_VALUE - arena) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return memory.resize(metadata + arena);
  }
}
