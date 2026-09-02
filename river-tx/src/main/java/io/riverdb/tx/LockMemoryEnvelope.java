package io.riverdb.tx;

/** Database-wide retained-byte envelope for lazily segmented lock state. */
public final class LockMemoryEnvelope {
  private final long maximumBytes;

  public LockMemoryEnvelope(long bytes) {
    if (bytes <= 0) throw new IllegalArgumentException("invalid lock memory envelope");
    maximumBytes = bytes;
  }

  public long maximumBytes() { return maximumBytes; }
}
