package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;

/** Caller-owned sequential cursor over one provider-owned forced WAL range. */
public final class LocalWalForcedCursor {
  private LocalWal owner;
  private long nextOffset;
  private long endOffset;
  private long remaining;

  public long remaining() { return remaining; }

  public StatusCode next(LocalWalReadResult result) {
    if (owner == null || result == null || remaining <= 0 || nextOffset >= endOffset) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = owner.read(nextOffset, result);
    if (!status.isOk()) return status;
    nextOffset = result.nextOffset();
    remaining--;
    if (remaining == 0 && nextOffset != endOffset) return StatusCode.CORRUPTION;
    return StatusCode.OK;
  }

  public StatusCode reset() {
    owner = null;
    nextOffset = endOffset = remaining = 0;
    return StatusCode.OK;
  }

  StatusCode open(LocalWal wal, long start, long end, long records) {
    if (owner != null || wal == null || start < 0 || end <= start || records <= 0) {
      return StatusCode.CONFLICT;
    }
    owner = wal;
    nextOffset = start;
    endOffset = end;
    remaining = records;
    return StatusCode.OK;
  }
}
