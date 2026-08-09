package io.riverdb.platform.file;

/** Caller-owned byte-count result for positional I/O. */
public final class IoResult {
  private int bytesTransferred;

  public int bytesTransferred() {
    return bytesTransferred;
  }

  public void setBytesTransferred(int bytesTransferred) {
    this.bytesTransferred = bytesTransferred;
  }

  public void reset() {
    bytesTransferred = 0;
  }
}
