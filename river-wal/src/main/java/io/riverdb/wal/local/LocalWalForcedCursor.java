package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;

/** Caller-owned sequential cursor over one provider-owned forced WAL range. */
public final class LocalWalForcedCursor {
  private LocalWal owner;
  private LocalWalForceTarget target;
  private long token;
  private long nextOffset;
  private long endOffset;
  private long remaining;

  public long remaining() { return remaining; }

  public StatusCode next(LocalWalReadResult result) {
    if (owner == null || !owner.ownsForceTarget(target, token)
        || result == null || remaining <= 0 || nextOffset >= endOffset) {
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
    target = null;
    token = 0;
    nextOffset = endOffset = remaining = 0;
    return StatusCode.OK;
  }

  StatusCode open(LocalWal wal, LocalWalForceTarget captured, long identity) {
    if (owner != null) return StatusCode.CONFLICT;
    owner = wal;
    target = captured;
    token = identity;
    nextOffset = captured.startOffset();
    endOffset = captured.endOffset();
    remaining = captured.recordCount();
    return StatusCode.OK;
  }
}
