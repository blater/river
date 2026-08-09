package io.riverdb.platform.file;

import io.riverdb.base.error.StatusCode;

/**
 * Caller-owned resumable state authenticated to one provider and issued install identity.
 * Never share it concurrently.
 */
public final class AtomicInstallProgress {
  Object owner;
  AtomicInstallPhase phase = AtomicInstallPhase.NEW;
  DirectoryDurability durability = DirectoryDurability.NOT_APPLIED;
  long requestVersion;
  long installValue;
  long providerGeneration;
  long pendingOperationId;
  int totalBytes;
  int bytesWritten;
  boolean completionPending;
  AtomicInstallPhase pendingPhase = AtomicInstallPhase.NEW;
  DirectoryDurability pendingDurability = DirectoryDurability.NOT_APPLIED;
  int pendingBytesWritten;

  /**
   * Releases terminal state for reuse. Active or pending work returns {@code CONFLICT} without
   * mutation; cancellation/recovery must first make the state terminal.
   */
  public StatusCode reset() {
    if (owner != null
        && phase != AtomicInstallPhase.VERIFIED
        && phase != AtomicInstallPhase.RECOVERY_REQUIRED) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    phase = AtomicInstallPhase.NEW;
    durability = DirectoryDurability.NOT_APPLIED;
    requestVersion = 0;
    installValue = 0;
    providerGeneration = 0;
    pendingOperationId = 0;
    totalBytes = 0;
    bytesWritten = 0;
    completionPending = false;
    pendingPhase = AtomicInstallPhase.NEW;
    pendingDurability = DirectoryDurability.NOT_APPLIED;
    pendingBytesWritten = 0;
    return StatusCode.OK;
  }
}
