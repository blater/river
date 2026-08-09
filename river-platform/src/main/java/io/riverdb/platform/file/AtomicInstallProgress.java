package io.riverdb.platform.file;

/** Caller-owned resumable state. It must not be shared by concurrent installs. */
public final class AtomicInstallProgress {
  private AtomicInstallPhase phase = AtomicInstallPhase.NEW;
  private DirectoryDurability durability = DirectoryDurability.NOT_APPLIED;
  private long requestVersion;
  private long providerGeneration;
  private int bytesWritten;
  private boolean completionPending;
  private AtomicInstallPhase pendingPhase = AtomicInstallPhase.NEW;
  private DirectoryDurability pendingDurability = DirectoryDurability.NOT_APPLIED;
  private int pendingBytesWritten;

  public AtomicInstallPhase phase() {
    return phase;
  }

  public DirectoryDurability durability() {
    return durability;
  }

  public int bytesWritten() {
    return bytesWritten;
  }

  public boolean completionPending() {
    return completionPending;
  }

  public boolean isComplete() {
    return phase == AtomicInstallPhase.VERIFIED && !completionPending;
  }

  public long requestVersion() {
    return requestVersion;
  }

  public long providerGeneration() {
    return providerGeneration;
  }

  public void begin(long requestVersion, long providerGeneration) {
    this.requestVersion = requestVersion;
    this.providerGeneration = providerGeneration;
  }

  public void advance(
      AtomicInstallPhase phase,
      DirectoryDurability durability,
      int bytesWritten) {
    this.phase = phase;
    this.durability = durability;
    this.bytesWritten = bytesWritten;
    completionPending = false;
  }

  public void delayCompletion(
      AtomicInstallPhase phase,
      DirectoryDurability durability,
      int bytesWritten) {
    pendingPhase = phase;
    pendingDurability = durability;
    pendingBytesWritten = bytesWritten;
    completionPending = true;
  }

  public void completePending() {
    advance(pendingPhase, pendingDurability, pendingBytesWritten);
  }

  public void requireRecovery() {
    phase = AtomicInstallPhase.RECOVERY_REQUIRED;
    durability = DirectoryDurability.UNKNOWN;
    completionPending = false;
  }

  public void reset() {
    phase = AtomicInstallPhase.NEW;
    durability = DirectoryDurability.NOT_APPLIED;
    requestVersion = 0;
    providerGeneration = 0;
    bytesWritten = 0;
    completionPending = false;
    pendingPhase = AtomicInstallPhase.NEW;
    pendingDurability = DirectoryDurability.NOT_APPLIED;
    pendingBytesWritten = 0;
  }
}
