package io.riverdb.platform.file;

/** Caller-owned file-size result. */
public final class FileSizeResult {
  private long sizeBytes;

  public long sizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }
}
