package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RetainedMemoryLease;

/** Splits one admitted workspace charge between independently reusable lanes. */
final class ProtocolPartitionedLease {
  private final RetainedMemoryLease parent;
  private final long[] charges;
  private final long baseline;

  ProtocolPartitionedLease(RetainedMemoryLease parent, int partitions) {
    if (parent == null || partitions <= 0) throw new IllegalArgumentException();
    this.parent = parent;
    charges = new long[partitions];
    baseline = parent.retainedBytes();
    if (baseline < 0) throw new IllegalArgumentException("parent charge");
  }

  RetainedMemoryLease lane(int index) {
    if (index < 0 || index >= charges.length) throw new IllegalArgumentException("index");
    return new Lane(index);
  }

  synchronized long retainedBytes() {
    long total = baseline;
    for (long charge : charges) total += charge;
    return total;
  }

  private final class Lane implements RetainedMemoryLease {
    private final int index;

    Lane(int index) { this.index = index; }

    @Override
    public StatusCode resize(long bytes) {
      if (bytes < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      synchronized (ProtocolPartitionedLease.this) {
        long total = 0;
        for (long charge : charges) total += charge;
        long next = nextTotal(total, index, bytes);
        if (next < 0) return StatusCode.RESOURCE_EXHAUSTED;
        StatusCode status = parent.resize(next);
        if (status.isOk()) charges[index] = bytes;
        return status;
      }
    }

    @Override
    public StatusCode awaitResize(long bytes) {
      if (bytes < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      synchronized (ProtocolPartitionedLease.this) {
        long total = 0;
        for (long charge : charges) total += charge;
        total = nextTotal(total, index, bytes);
        if (total < 0) return StatusCode.RESOURCE_EXHAUSTED;
        StatusCode status = parent.awaitResize(total);
        if (status.isOk()) charges[index] = bytes;
        return status;
      }
    }

    @Override
    public long retainedBytes() {
      synchronized (ProtocolPartitionedLease.this) { return charges[index]; }
    }
  }

  private long nextTotal(long total, int index, long bytes) {
    long delta = total - charges[index];
    if (delta > Long.MAX_VALUE - bytes) return -1;
    delta += bytes;
    return baseline > Long.MAX_VALUE - delta ? -1 : baseline + delta;
  }
}
