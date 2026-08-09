package io.riverdb.platform.file;

/** Caller-owned read-only view authenticated and populated by the owning installer. */
public final class AtomicInstallSnapshot {
  private AtomicInstallPhase phase = AtomicInstallPhase.NEW;
  private AtomicInstallPhase appliedPhase = AtomicInstallPhase.NEW;
  private DirectoryDurability durability = DirectoryDurability.NOT_APPLIED;
  private DirectoryDurability appliedDurability = DirectoryDurability.NOT_APPLIED;
  private int bytesWritten;
  private long pendingOperationId;
  private boolean completionPending;

  public AtomicInstallPhase phase() {
    return phase;
  }

  public AtomicInstallPhase appliedPhase() {
    return appliedPhase;
  }

  public DirectoryDurability durability() {
    return durability;
  }

  public DirectoryDurability appliedDurability() {
    return appliedDurability;
  }

  public int bytesWritten() {
    return bytesWritten;
  }

  public long pendingOperationId() {
    return pendingOperationId;
  }

  public boolean completionPending() {
    return completionPending;
  }

  public boolean isComplete() {
    return phase == AtomicInstallPhase.VERIFIED && !completionPending;
  }

  public void set(
      AtomicInstallPhase phase,
      AtomicInstallPhase appliedPhase,
      DirectoryDurability durability,
      DirectoryDurability appliedDurability,
      int bytesWritten,
      long pendingOperationId,
      boolean completionPending) {
    this.phase = phase;
    this.appliedPhase = appliedPhase;
    this.durability = durability;
    this.appliedDurability = appliedDurability;
    this.bytesWritten = bytesWritten;
    this.pendingOperationId = pendingOperationId;
    this.completionPending = completionPending;
  }

  public void reset() {
    phase = AtomicInstallPhase.NEW;
    appliedPhase = AtomicInstallPhase.NEW;
    durability = DirectoryDurability.NOT_APPLIED;
    appliedDurability = DirectoryDurability.NOT_APPLIED;
    bytesWritten = 0;
    pendingOperationId = 0;
    completionPending = false;
  }
}
