package io.riverdb.testkit.io;

/** Caller-owned snapshot of deterministic model allocation and payload-copy counts. */
public final class FileModelCounters {
  private long handleAllocations;
  private long fileAllocations;
  private long readCopyBytes;
  private long writeCopyBytes;
  private long durableCopyBytes;
  private long recoveryCopyBytes;

  public long handleAllocations() {
    return handleAllocations;
  }

  public long fileAllocations() {
    return fileAllocations;
  }

  public long readCopyBytes() {
    return readCopyBytes;
  }

  public long writeCopyBytes() {
    return writeCopyBytes;
  }

  public long durableCopyBytes() {
    return durableCopyBytes;
  }

  public long recoveryCopyBytes() {
    return recoveryCopyBytes;
  }

  void set(
      long handleAllocations,
      long fileAllocations,
      long readCopyBytes,
      long writeCopyBytes,
      long durableCopyBytes,
      long recoveryCopyBytes) {
    this.handleAllocations = handleAllocations;
    this.fileAllocations = fileAllocations;
    this.readCopyBytes = readCopyBytes;
    this.writeCopyBytes = writeCopyBytes;
    this.durableCopyBytes = durableCopyBytes;
    this.recoveryCopyBytes = recoveryCopyBytes;
  }
}
