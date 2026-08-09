package io.riverdb.bench.prototype;

/** Caller-owned output for a page-protection model run. */
public final class PageProtectionResult {
  long walBytes;
  long stagingBytes;
  long dataBytes;
  long copiedBytes;
  long walForceCalls;
  long stagingForceCalls;
  long dataForceCalls;
  long dirties;
  long firstDirtyPages;
  long redirties;

  public long walBytes() {
    return walBytes;
  }

  public long dataBytes() {
    return dataBytes;
  }

  public long stagingBytes() {
    return stagingBytes;
  }

  public long totalBytes() {
    return walBytes + stagingBytes + dataBytes;
  }

  public long copiedBytes() {
    return copiedBytes;
  }

  public long forceCalls() {
    return walForceCalls + stagingForceCalls + dataForceCalls;
  }

  public long walForceCalls() {
    return walForceCalls;
  }

  public long stagingForceCalls() {
    return stagingForceCalls;
  }

  public long dataForceCalls() {
    return dataForceCalls;
  }

  public long dirties() {
    return dirties;
  }

  public long uniqueDirtyPages() {
    return firstDirtyPages;
  }

  public long firstDirtyPages() {
    return firstDirtyPages;
  }

  public long redirties() {
    return redirties;
  }
}
