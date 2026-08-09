package io.riverdb.platform.file;

/** Caller-owned result for synchronous directory operations. */
public final class DirectoryOperationResult {
  private DurableFile file;
  private DirectoryDurability durability = DirectoryDurability.NOT_APPLIED;

  public DurableFile file() {
    return file;
  }

  public DirectoryDurability durability() {
    return durability;
  }

  public void set(DurableFile file, DirectoryDurability durability) {
    this.file = file;
    this.durability = durability;
  }

  public void reset() {
    file = null;
    durability = DirectoryDurability.NOT_APPLIED;
  }
}
