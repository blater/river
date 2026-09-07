package io.riverdb.platform.riverd;

/** Platform-neutral stable identity for one verified filesystem object. */
public final class FileIdentity {
  private final long volume;
  private final long high;
  private final long low;

  public FileIdentity(long volume, long high, long low) {
    this.volume = volume;
    this.high = high;
    this.low = low;
  }

  public long volume() {
    return volume;
  }

  public long high() {
    return high;
  }

  public long low() {
    return low;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof FileIdentity identity)) return false;
    return volume == identity.volume && high == identity.high && low == identity.low;
  }

  @Override
  public int hashCode() {
    long value = volume * 31 + high;
    value = value * 31 + low;
    return (int) (value ^ (value >>> 32));
  }

  @Override
  public String toString() {
    return volume + ":" + high + ":" + low;
  }
}
