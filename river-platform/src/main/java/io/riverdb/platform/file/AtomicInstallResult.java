package io.riverdb.platform.file;

/** Caller-owned report for the last attempted or completed install half-step. */
public final class AtomicInstallResult {
  private AtomicInstallStep step = AtomicInstallStep.NONE;
  private AtomicInstallPhase phaseBefore = AtomicInstallPhase.NEW;
  private AtomicInstallPhase phaseAfter = AtomicInstallPhase.NEW;
  private DirectoryDurability durability = DirectoryDurability.NOT_APPLIED;
  private int bytesTransferred;
  private boolean completionPending;

  public AtomicInstallStep step() {
    return step;
  }

  public AtomicInstallPhase phaseBefore() {
    return phaseBefore;
  }

  public AtomicInstallPhase phaseAfter() {
    return phaseAfter;
  }

  public DirectoryDurability durability() {
    return durability;
  }

  public int bytesTransferred() {
    return bytesTransferred;
  }

  public boolean completionPending() {
    return completionPending;
  }

  public void set(
      AtomicInstallStep step,
      AtomicInstallPhase phaseBefore,
      AtomicInstallPhase phaseAfter,
      DirectoryDurability durability,
      int bytesTransferred,
      boolean completionPending) {
    this.step = step;
    this.phaseBefore = phaseBefore;
    this.phaseAfter = phaseAfter;
    this.durability = durability;
    this.bytesTransferred = bytesTransferred;
    this.completionPending = completionPending;
  }

  public void reset() {
    step = AtomicInstallStep.NONE;
    phaseBefore = AtomicInstallPhase.NEW;
    phaseAfter = AtomicInstallPhase.NEW;
    durability = DirectoryDurability.NOT_APPLIED;
    bytesTransferred = 0;
    completionPending = false;
  }
}
