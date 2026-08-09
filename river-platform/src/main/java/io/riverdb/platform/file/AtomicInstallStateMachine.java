package io.riverdb.platform.file;

import io.riverdb.base.error.StatusCode;

/**
 * Provider-owned authority for monotonic progress transitions.
 *
 * <p>Each installer owns one private instance and never exposes it to callers. The instance is the
 * opaque capability: its identity binds a progress carrier to that provider, so another provider
 * or a caller-created state machine cannot inspect, resume, or promote the provider-owned state.
 */
public final class AtomicInstallStateMachine {
  private final Object capability = new Object();
  private long nextOperationId = 1;

  public StatusCode resume(
      AtomicInstallProgress progress,
      long requestVersion,
      long providerGeneration,
      int totalBytes) {
    if (requestVersion == 0 || providerGeneration == 0 || totalBytes < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (progress.owner == null) {
      if (progress.phase != AtomicInstallPhase.NEW
          || progress.requestVersion != 0
          || progress.providerGeneration != 0
          || progress.bytesWritten != 0
          || progress.completionPending) {
        return StatusCode.INVARIANT_BROKEN;
      }
      progress.owner = capability;
      progress.requestVersion = requestVersion;
      progress.providerGeneration = providerGeneration;
      progress.totalBytes = totalBytes;
      return StatusCode.OK;
    }
    if (progress.owner != capability) {
      return StatusCode.NOT_OWNER;
    }
    if (progress.requestVersion != requestVersion || progress.totalBytes != totalBytes) {
      return StatusCode.CONFLICT;
    }
    if (progress.providerGeneration != providerGeneration) {
      requireRecovery(progress);
      return StatusCode.CANCELLED;
    }
    return StatusCode.OK;
  }

  public StatusCode transition(
      AtomicInstallProgress progress,
      AtomicInstallPhase expected,
      AtomicInstallPhase next,
      DirectoryDurability durability,
      int bytesWritten) {
    StatusCode status = validateTransition(
        progress, expected, next, durability, bytesWritten);
    if (!status.isOk()) {
      return status;
    }
    progress.phase = next;
    progress.durability = durability;
    progress.bytesWritten = bytesWritten;
    clearPending(progress);
    return StatusCode.OK;
  }

  public StatusCode delayCompletion(
      AtomicInstallProgress progress,
      AtomicInstallPhase expected,
      AtomicInstallPhase applied,
      DirectoryDurability durability,
      int bytesWritten) {
    StatusCode status = validateTransition(
        progress, expected, applied, durability, bytesWritten);
    if (!status.isOk()) {
      return status;
    }
    long operationId = nextOperationId++;
    if (operationId == 0) {
      operationId = nextOperationId++;
    }
    progress.pendingOperationId = operationId;
    progress.pendingPhase = applied;
    progress.pendingDurability = durability;
    progress.pendingBytesWritten = bytesWritten;
    progress.completionPending = true;
    return StatusCode.OK;
  }

  public StatusCode completePending(AtomicInstallProgress progress) {
    StatusCode status = validateOwner(progress);
    if (!status.isOk()) {
      return status;
    }
    if (!progress.completionPending || progress.pendingOperationId == 0) {
      return StatusCode.CONFLICT;
    }
    progress.phase = progress.pendingPhase;
    progress.durability = progress.pendingDurability;
    progress.bytesWritten = progress.pendingBytesWritten;
    clearPending(progress);
    return StatusCode.OK;
  }

  public StatusCode requireRecovery(AtomicInstallProgress progress) {
    StatusCode status = validateOwner(progress);
    if (!status.isOk()) {
      return status;
    }
    progress.phase = AtomicInstallPhase.RECOVERY_REQUIRED;
    progress.durability = DirectoryDurability.UNKNOWN;
    clearPending(progress);
    return StatusCode.OK;
  }

  public StatusCode validateOwner(AtomicInstallProgress progress) {
    return progress.owner == capability ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  public AtomicInstallPhase phase(AtomicInstallProgress progress) {
    return progress.owner == capability
        ? progress.phase
        : AtomicInstallPhase.RECOVERY_REQUIRED;
  }

  public DirectoryDurability durability(AtomicInstallProgress progress) {
    return progress.owner == capability
        ? progress.durability
        : DirectoryDurability.UNKNOWN;
  }

  public int bytesWritten(AtomicInstallProgress progress) {
    return progress.owner == capability ? progress.bytesWritten : 0;
  }

  public long providerGeneration(AtomicInstallProgress progress) {
    return progress.owner == capability ? progress.providerGeneration : 0;
  }

  public boolean completionPending(AtomicInstallProgress progress) {
    return progress.owner == capability && progress.completionPending;
  }

  public boolean isComplete(AtomicInstallProgress progress) {
    return progress.owner == capability
        && progress.phase == AtomicInstallPhase.VERIFIED
        && !progress.completionPending;
  }

  public StatusCode snapshot(
      AtomicInstallProgress progress,
      AtomicInstallSnapshot result) {
    result.reset();
    StatusCode status = validateOwner(progress);
    if (!status.isOk()) {
      return status;
    }
    result.set(
        progress.phase,
        progress.completionPending ? progress.pendingPhase : progress.phase,
        progress.durability,
        progress.completionPending ? progress.pendingDurability : progress.durability,
        progress.bytesWritten,
        progress.pendingOperationId,
        progress.completionPending);
    return StatusCode.OK;
  }

  private StatusCode validateTransition(
      AtomicInstallProgress progress,
      AtomicInstallPhase expected,
      AtomicInstallPhase next,
      DirectoryDurability durability,
      int bytesWritten) {
    StatusCode status = validateOwner(progress);
    if (!status.isOk()) {
      return status;
    }
    if (progress.completionPending
        || progress.phase != expected
        || !allowed(expected, next)
        || bytesWritten < progress.bytesWritten
        || bytesWritten > progress.totalBytes
        || durability == null
        || durability == DirectoryDurability.UNKNOWN
        || durability.ordinal() < progress.durability.ordinal()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    if (next.ordinal() >= AtomicInstallPhase.CONTENT_WRITTEN.ordinal()
        && bytesWritten != progress.totalBytes) {
      return StatusCode.INVARIANT_BROKEN;
    }
    return StatusCode.OK;
  }

  private static boolean allowed(AtomicInstallPhase current, AtomicInstallPhase next) {
    return switch (current) {
      case NEW -> next == AtomicInstallPhase.TEMP_CREATED;
      case TEMP_CREATED -> next == AtomicInstallPhase.TEMP_CREATED
          || next == AtomicInstallPhase.CONTENT_WRITTEN;
      case CONTENT_WRITTEN -> next == AtomicInstallPhase.CONTENT_FORCED;
      case CONTENT_FORCED -> next == AtomicInstallPhase.DESTINATION_REPLACED;
      case DESTINATION_REPLACED -> next == AtomicInstallPhase.DIRECTORY_FORCED;
      case DIRECTORY_FORCED -> next == AtomicInstallPhase.VERIFIED;
      case VERIFIED, RECOVERY_REQUIRED -> false;
    };
  }

  private static void clearPending(AtomicInstallProgress progress) {
    progress.pendingOperationId = 0;
    progress.pendingPhase = AtomicInstallPhase.NEW;
    progress.pendingDurability = DirectoryDurability.NOT_APPLIED;
    progress.pendingBytesWritten = 0;
    progress.completionPending = false;
  }
}
