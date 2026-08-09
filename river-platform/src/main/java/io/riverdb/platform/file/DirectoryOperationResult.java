package io.riverdb.platform.file;

/** Caller-owned result for directory operations, including ambiguous half-steps. */
public final class DirectoryOperationResult {
  private DurableFile file;
  private DirectoryDurability durability = DirectoryDurability.NOT_APPLIED;
  private boolean completionPending;

  public DurableFile file() {
    return file;
  }

  public DirectoryDurability durability() {
    return durability;
  }

  public boolean completionPending() {
    return completionPending;
  }

  public void set(
      DurableFile file,
      DirectoryDurability durability,
      boolean completionPending) {
    this.file = file;
    this.durability = durability;
    this.completionPending = completionPending;
  }

  public void reset() {
    file = null;
    durability = DirectoryDurability.NOT_APPLIED;
    completionPending = false;
  }
}
